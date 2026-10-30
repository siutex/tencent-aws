locals {
  alb_idle_timeout = 120
}
resource "aws_lb" "static" {
  name                       = "${var.name}-static"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  idle_timeout               = local.alb_idle_timeout
  enable_deletion_protection = true
}
resource "aws_lb_target_group" "proxy" {
  name        = "${var.name}-proxy"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  # Let in-flight object streams finish on scale-in and deployments (ECS sends SIGTERM only
  # after deregistration completes).
  deregistration_delay = local.alb_idle_timeout
  health_check {
    path                = "/_origin_health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 30
    matcher             = "200"
  }
}
resource "aws_lb_listener" "static" {
  load_balancer_arn = aws_lb.static.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.static_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.proxy.arn
  }
}
resource "aws_route53_record" "static" {
  zone_id = var.route53_zone_id
  name    = var.static_origin_hostname
  type    = "A"
  alias {
    name                   = aws_lb.static.dns_name
    zone_id                = aws_lb.static.zone_id
    evaluate_target_health = true
  }
}
resource "aws_cloudwatch_log_group" "proxy" {
  name              = "/ecs/${var.name}-proxy"
  retention_in_days = 30
}
resource "aws_ecs_cluster" "origin" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
locals {
  ecs_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Action = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:${data.aws_partition.current.partition}:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:*" }
      }
    }]
  })
}
resource "aws_iam_role" "execution" {
  name_prefix        = "${var.name}-exec-"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
resource "aws_iam_role" "proxy" {
  name_prefix        = "${var.name}-proxy-"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy" "proxy" {
  role = aws_iam_role.proxy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(local.static_secret_policy, [
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = ["${local.bucket_arn}/${var.object_prefix}*"] }
      ], var.bucket_kms_key_arn == null ? [] : [
      { Effect = "Allow", Action = ["kms:Decrypt"], Resource = [var.bucket_kms_key_arn] }
    ])
  })
}
resource "aws_ecs_task_definition" "proxy" {
  family                   = "${var.name}-proxy"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  task_role_arn            = aws_iam_role.proxy.arn
  execution_role_arn       = aws_iam_role.execution.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name            = "proxy", image = var.proxy_image, essential = true
    user            = "10001:10001", readonlyRootFilesystem = true
    linuxParameters = { initProcessEnabled = true }
    portMappings    = [{ containerPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "AWS_REGION", value = var.region },
      { name = "METRICS_SERVICE", value = var.name },
      { name = "BUCKET", value = var.bucket_name },
      { name = "PREFIX", value = var.object_prefix },
      { name = "SECRET_ARN", value = var.static_secret_arn },
      { name = "DEFAULT_CACHE_CONTROL", value = var.default_cache_control },
      { name = "PROXY_MAX_CONCURRENCY", value = tostring(var.proxy_max_concurrency) },
      # Jetty idle timeout must outlive the ALB idle timeout (avoids sporadic 502s).
      { name = "PROXY_IDLE_TIMEOUT_SECONDS", value = tostring(local.alb_idle_timeout + 5) }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.proxy.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "proxy"
      }
    }
  }])
}
resource "aws_ecs_service" "proxy" {
  name                               = "${var.name}-proxy"
  cluster                            = aws_ecs_cluster.origin.id
  task_definition                    = aws_ecs_task_definition.proxy.arn
  desired_count                      = var.proxy_min_tasks
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  health_check_grace_period_seconds  = 60
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.proxy.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.proxy.arn
    container_name   = "proxy"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener.static, aws_iam_role_policy.proxy, aws_iam_role_policy_attachment.execution, aws_s3_bucket_public_access_block.assets]
  lifecycle { ignore_changes = [desired_count] }
}
resource "aws_appautoscaling_target" "proxy" {
  max_capacity       = var.proxy_max_tasks
  min_capacity       = var.proxy_min_tasks
  resource_id        = "service/${aws_ecs_cluster.origin.name}/${aws_ecs_service.proxy.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
  lifecycle {
    precondition {
      condition     = var.proxy_max_tasks >= var.proxy_min_tasks
      error_message = "proxy_max_tasks must be >= proxy_min_tasks."
    }
  }
}
# Two target-tracking policies: scale out when either one asks, scale in only when both
# allow it. Streaming is I/O bound, so request load usually saturates threads before CPU.
resource "aws_appautoscaling_policy" "proxy" {
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.proxy.resource_id
  scalable_dimension = aws_appautoscaling_target.proxy.scalable_dimension
  service_namespace  = aws_appautoscaling_target.proxy.service_namespace
  target_tracking_scaling_policy_configuration {
    target_value = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
    scale_in_cooldown  = 120
    scale_out_cooldown = 30
  }
}
resource "aws_appautoscaling_policy" "proxy_requests" {
  name               = "${var.name}-requests"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.proxy.resource_id
  scalable_dimension = aws_appautoscaling_target.proxy.scalable_dimension
  service_namespace  = aws_appautoscaling_target.proxy.service_namespace
  target_tracking_scaling_policy_configuration {
    target_value = var.proxy_target_requests_per_minute
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.static.arn_suffix}/${aws_lb_target_group.proxy.arn_suffix}"
    }
    scale_in_cooldown  = 300
    scale_out_cooldown = 30
  }
  depends_on = [aws_lb_listener.static]
}
