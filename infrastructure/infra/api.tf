resource "aws_lb" "api" {
  name                             = "${var.name}-api"
  internal                         = true
  load_balancer_type               = "network"
  subnets                          = var.private_subnet_ids
  security_groups                  = [aws_security_group.nlb.id]
  enable_cross_zone_load_balancing = true
  enable_deletion_protection       = true
}
resource "aws_lb_target_group" "api" {
  name               = "${var.name}-api"
  vpc_id             = var.vpc_id
  target_type        = "ip"
  port               = var.backend_port
  protocol           = "TCP"
  preserve_client_ip = false
  health_check {
    protocol = "HTTP"
    path     = var.backend_health_path
    port     = "traffic-port"
    matcher  = "200-399"
  }
}
resource "aws_lb_target_group_attachment" "api" {
  for_each         = var.backend_ips
  target_group_arn = aws_lb_target_group.api.arn
  target_id        = each.value
  port             = var.backend_port
}
resource "aws_lb_listener" "api" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "TLS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.api_nlb_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
resource "aws_apigatewayv2_vpc_link" "api" {
  name               = var.name
  security_group_ids = [aws_security_group.vpc_link.id]
  subnet_ids         = var.private_subnet_ids
}
resource "aws_apigatewayv2_api" "api" {
  name          = var.name
  protocol_type = "HTTP"
}
resource "aws_apigatewayv2_integration" "api" {
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  integration_uri        = aws_lb_listener.api.arn
  connection_type        = "VPC_LINK"
  connection_id          = aws_apigatewayv2_vpc_link.api.id
  payload_format_version = "1.0"
  timeout_milliseconds   = 29000
  tls_config { server_name_to_verify = var.api_nlb_tls_hostname }
  request_parameters = {
    "overwrite:path"                = "$request.path"
    "remove:header.x-origin-verify" = ""
  }
}
resource "aws_apigatewayv2_route" "api" {
  for_each           = toset(["ANY /api", "ANY /api/{proxy+}"])
  api_id             = aws_apigatewayv2_api.api.id
  route_key          = each.value
  target             = "integrations/${aws_apigatewayv2_integration.api.id}"
  authorization_type = "CUSTOM"
  authorizer_id      = aws_apigatewayv2_authorizer.origin.id
}
resource "aws_cloudwatch_log_group" "api" {
  name              = "/aws/apigateway/${var.name}"
  retention_in_days = 30
}
resource "aws_apigatewayv2_stage" "api" {
  api_id      = aws_apigatewayv2_api.api.id
  name        = "$default"
  auto_deploy = true
  default_route_settings {
    throttling_burst_limit   = var.api_burst_limit
    throttling_rate_limit    = var.api_rate_limit
    detailed_metrics_enabled = true
  }
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api.arn
    # sourceIp is the TCP peer: anything outside the EdgeOne list is a direct hit on execute-api.
    format = jsonencode({
      requestId         = "$context.requestId"
      sourceIp          = "$context.identity.sourceIp"
      route             = "$context.routeKey"
      status            = "$context.status"
      latency           = "$context.responseLatency"
      authorizerError   = "$context.authorizer.error"
      integrationStatus = "$context.integrationStatus"
      integrationError  = "$context.integrationErrorMessage"
    })
  }
}
resource "aws_iam_role" "authorizer" {
  name_prefix = "${var.name}-auth-"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" } }]
  })
}
resource "aws_cloudwatch_log_group" "authorizer" {
  name              = "/aws/lambda/${var.name}-authorizer"
  retention_in_days = 30
}
resource "aws_iam_role_policy" "authorizer" {
  role = aws_iam_role.authorizer.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(local.api_secret_policy, [{
      Effect   = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = ["${aws_cloudwatch_log_group.authorizer.arn}:*"]
      }, {
      # EC2 supports resource-level permissions for this read action.
      Effect   = "Allow", Action = ["ec2:GetManagedPrefixListEntries"]
      Resource = [aws_ec2_managed_prefix_list.edgeone.arn]
    }])
  })
}
locals {
  # Build in this repository: python3 scripts/build_authorizer.py
  authorizer_package = coalesce(var.authorizer_package, "${path.module}/../build/authorizer.zip")
}
# Versioned Python package built from the same infrastructure commit.
resource "aws_lambda_function" "authorizer" {
  function_name    = "${var.name}-authorizer"
  role             = aws_iam_role.authorizer.arn
  runtime          = "python3.13"
  handler          = "authorizer.handler"
  filename         = local.authorizer_package
  source_code_hash = filebase64sha256(local.authorizer_package)
  timeout          = 10
  memory_size      = var.authorizer_memory_mb
  publish          = true
  environment {
    variables = {
      SECRET_ARN              = var.api_secret_arn
      PREFIX_LIST_ID          = aws_ec2_managed_prefix_list.edgeone.id
      CIDR_MAX_STALE_SECONDS  = tostring(var.authorizer_cidr_max_stale_seconds)
      TOKEN_MAX_STALE_SECONDS = tostring(var.authorizer_token_max_stale_seconds)
    }
  }
  depends_on = [aws_iam_role_policy.authorizer, aws_cloudwatch_log_group.authorizer]
}
resource "aws_lambda_alias" "authorizer_live" {
  name             = "live"
  function_name    = aws_lambda_function.authorizer.function_name
  function_version = aws_lambda_function.authorizer.version
}
resource "aws_apigatewayv2_authorizer" "origin" {
  api_id                            = aws_apigatewayv2_api.api.id
  name                              = "origin-token"
  authorizer_type                   = "REQUEST"
  authorizer_uri                    = aws_lambda_alias.authorizer_live.invoke_arn
  authorizer_payload_format_version = "2.0"
  enable_simple_responses           = true
  identity_sources                  = ["$request.header.x-origin-verify"]
  # Decisions depend on the source IP too. If caching is ever enabled, add
  # "$context.identity.sourceIp" to identity_sources, otherwise a cached "allow" for a
  # token would also apply to requests from other addresses.
  authorizer_result_ttl_in_seconds = 0
}
resource "aws_lambda_permission" "authorizer" {
  statement_id  = "AllowApiGatewayAuthorizer"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.authorizer.function_name
  qualifier     = aws_lambda_alias.authorizer_live.name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api.execution_arn}/authorizers/${aws_apigatewayv2_authorizer.origin.id}"
}
