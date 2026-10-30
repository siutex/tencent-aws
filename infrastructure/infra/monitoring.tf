resource "aws_cloudwatch_metric_alarm" "authorizer_dependency" {
  for_each            = toset(["prefix_list_unavailable", "secret_unavailable", "stale_cache_used", "dependency_denied", "initialization_unavailable"])
  alarm_name          = "${var.name}-${each.value}"
  namespace           = "EdgeOne/Origin"
  metric_name         = each.value
  dimensions          = { Function = aws_lambda_function.authorizer.function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns
}
resource "aws_cloudwatch_metric_alarm" "authorizer_runtime" {
  for_each            = toset(["Errors", "Throttles"])
  alarm_name          = "${var.name}-lambda-${each.value}"
  namespace           = "AWS/Lambda"
  metric_name         = each.value
  dimensions          = { FunctionName = aws_lambda_function.authorizer.function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
}
resource "aws_cloudwatch_metric_alarm" "proxy_rejected" {
  alarm_name          = "${var.name}-proxy-rejected"
  namespace           = "EdgeOne/Proxy"
  metric_name         = "Rejected"
  dimensions          = { Service = var.name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
}
resource "aws_cloudwatch_metric_alarm" "proxy_capacity" {
  alarm_name          = "${var.name}-proxy-capacity"
  namespace           = "EdgeOne/Proxy"
  metric_name         = "PeakActive"
  dimensions          = { Service = var.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = var.proxy_max_concurrency * 0.8
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
}

# All alarms above treat missing data as OK. If EMF from the proxy stops arriving (logging
# pipeline, METRICS_SERVICE mismatch, crashed tasks), they would stay silent: watch a heartbeat.
resource "aws_cloudwatch_metric_alarm" "proxy_metrics_missing" {
  alarm_name          = "${var.name}-proxy-metrics-missing"
  namespace           = "EdgeOne/Proxy"
  metric_name         = "Active"
  dimensions          = { Service = var.name }
  statistic           = "SampleCount"
  period              = 300
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns
}

# Load balancer and API errors are visible without custom metrics. Thresholds are a starting
# point; tune them after the load test.
locals {
  alb_alarms = {
    unhealthy_targets = { metric = "UnHealthyHostCount", statistic = "Maximum", threshold = 0, period = 60, target_group = true }
    target_5xx        = { metric = "HTTPCode_Target_5XX_Count", statistic = "Sum", threshold = 10, period = 300, target_group = false }
    elb_5xx           = { metric = "HTTPCode_ELB_5XX_Count", statistic = "Sum", threshold = 10, period = 300, target_group = false }
  }
}
resource "aws_cloudwatch_metric_alarm" "alb" {
  for_each    = local.alb_alarms
  alarm_name  = "${var.name}-alb-${each.key}"
  namespace   = "AWS/ApplicationELB"
  metric_name = each.value.metric
  dimensions = each.value.target_group ? {
    LoadBalancer = aws_lb.static.arn_suffix
    TargetGroup  = aws_lb_target_group.proxy.arn_suffix
  } : { LoadBalancer = aws_lb.static.arn_suffix }
  statistic           = each.value.statistic
  period              = each.value.period
  evaluation_periods  = 1
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
}
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${var.name}-api-5xx"
  namespace           = "AWS/ApiGateway"
  metric_name         = "5xx"
  dimensions          = { ApiId = aws_apigatewayv2_api.api.id }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
}
