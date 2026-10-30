# Offline plan tests: mocked AWS provider, no credentials, no AWS calls.
# Run: terraform -chdir=infra test   (Terraform >= 1.7 or OpenTofu >= 1.8)

mock_provider "aws" {
  mock_data "aws_partition" {
    defaults = { partition = "aws", reverse_dns_prefix = "com.amazonaws", dns_suffix = "amazonaws.com" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_servicequotas_service_quota" {
    defaults = { value = 200 }
  }
  mock_data "aws_route_tables" {
    defaults = { ids = ["rtb-0123456789abcdef0"] }
  }
  mock_data "aws_vpc_endpoint" {
    defaults = { state = "available", route_table_ids = ["rtb-0123456789abcdef0"] }
  }
  # Mocked computed ARNs must be syntactically valid for provider-side validation.
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:loadbalancer/app/mock/0123456789abcdef", arn_suffix = "app/mock/0123456789abcdef" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:targetgroup/mock/0123456789abcdef", arn_suffix = "targetgroup/mock/0123456789abcdef" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:eu-central-1:123456789012:listener/app/mock/0123456789abcdef/0123456789abcdef" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:eu-central-1:123456789012:log-group:mock" }
  }
  mock_resource "aws_lambda_function" {
    defaults = { arn = "arn:aws:lambda:eu-central-1:123456789012:function:mock", version = "1", invoke_arn = "arn:aws:apigateway:eu-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:eu-central-1:123456789012:function:mock/invocations" }
  }
  mock_resource "aws_lambda_alias" {
    defaults = { arn = "arn:aws:lambda:eu-central-1:123456789012:function:mock:live", invoke_arn = "arn:aws:apigateway:eu-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:eu-central-1:123456789012:function:mock:live/invocations" }
  }
  mock_resource "aws_ec2_managed_prefix_list" {
    defaults = { id = "pl-0123456789abcdef0", arn = "arn:aws:ec2:eu-central-1:123456789012:prefix-list/pl-0123456789abcdef0" }
  }
  mock_resource "aws_apigatewayv2_api" {
    defaults = { api_endpoint = "https://abc123.execute-api.eu-central-1.amazonaws.com", execution_arn = "arn:aws:execute-api:eu-central-1:123456789012:abc123" }
  }
}

variables {
  alarm_action_arns         = ["arn:aws:sns:eu-central-1:123456789012:operations"]
  vpc_id                    = "vpc-0123456789abcdef0"
  public_subnet_ids         = ["subnet-0000000000000000a", "subnet-0000000000000000b"]
  private_subnet_ids        = ["subnet-0000000000000000c", "subnet-0000000000000000d"]
  route53_zone_id           = "Z0123456789ABCDEFGHIJ"
  static_origin_hostname    = "static-origin.example.com"
  static_certificate_arn    = "arn:aws:acm:eu-central-1:123456789012:certificate/00000000-0000-0000-0000-000000000001"
  proxy_image               = "123456789012.dkr.ecr.eu-central-1.amazonaws.com/edgeone-origin-proxy@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  bucket_name               = "example-assets-bucket"
  static_secret_arn         = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:edgeone-origin/static-origin-token-AAAAAA"
  api_secret_arn            = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:edgeone-origin/api-origin-token-BBBBBB"
  edgeone_ipv4_cidrs        = ["43.0.0.0/24", "43.0.1.0/24", "101.0.0.0/22"]
  backend_ips               = ["10.0.11.10", "10.0.12.10"]
  backend_security_group_id = "sg-0123456789abcdef0"
  api_nlb_certificate_arn   = "arn:aws:acm:eu-central-1:123456789012:certificate/00000000-0000-0000-0000-000000000002"
  api_nlb_tls_hostname      = "api-internal.example.com"
  api_rate_limit            = 500
  api_burst_limit           = 1000
  authorizer_package        = "tests/fixtures/authorizer-placeholder.zip"
}

run "plan_wires_single_sources" {
  command = plan

  assert {
    condition     = length(aws_ec2_managed_prefix_list.edgeone.entry) == 3 && aws_ec2_managed_prefix_list.edgeone.max_entries == 200
    error_message = "Prefix list must contain every EdgeOne CIDR."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.edgeone.cidr_ipv4 == null
    error_message = "ALB ingress must reference the prefix list, not individual CIDRs."
  }
  assert {
    condition     = aws_lambda_function.authorizer.environment[0].variables.SECRET_ARN == var.api_secret_arn
    error_message = "Authorizer must read the API secret."
  }
  assert {
    condition     = aws_lambda_function.authorizer.environment[0].variables.PREFIX_LIST_ID == aws_ec2_managed_prefix_list.edgeone.id
    error_message = "Authorizer must read the same prefix list as the ALB rule."
  }
  assert {
    condition     = aws_lambda_function.authorizer.runtime == "python3.13" && aws_lambda_function.authorizer.publish && length(aws_lambda_function.authorizer.snap_start) == 0
    error_message = "Authorizer must use published Python versions without SnapStart."
  }
  assert {
    condition     = aws_apigatewayv2_authorizer.origin.authorizer_uri == aws_lambda_alias.authorizer_live.invoke_arn && aws_lambda_permission.authorizer.qualifier == "live"
    error_message = "API Gateway must invoke the versioned alias, not $LATEST."
  }
  assert {
    condition     = strcontains(aws_iam_role_policy.authorizer.policy, "ec2:GetManagedPrefixListEntries")
    error_message = "Authorizer must be allowed to read the prefix list."
  }
  assert {
    condition     = strcontains(aws_iam_role_policy.proxy.policy, var.static_secret_arn) && !strcontains(aws_iam_role_policy.proxy.policy, var.api_secret_arn)
    error_message = "Proxy role must read only the static secret."
  }
  assert {
    condition     = strcontains(aws_iam_role_policy.authorizer.policy, var.api_secret_arn) && !strcontains(aws_iam_role_policy.authorizer.policy, var.static_secret_arn)
    error_message = "Authorizer role must read only the API secret."
  }
  assert {
    condition     = strcontains(aws_ecs_task_definition.proxy.container_definitions, "\"PROXY_IDLE_TIMEOUT_SECONDS\",\"value\":\"125\"") && aws_lb.static.idle_timeout == 120
    error_message = "Proxy idle timeout must be ALB idle timeout + 5 s."
  }
  assert {
    condition     = aws_lb_target_group.proxy.deregistration_delay == "120"
    error_message = "Targets must drain for the ALB idle timeout."
  }
  assert {
    condition     = strcontains(aws_ecs_task_definition.proxy.container_definitions, "\"DEFAULT_CACHE_CONTROL\",\"value\":\"no-store\"")
    error_message = "Default Cache-Control must stay no-store unless set explicitly."
  }
  assert {
    condition     = aws_appautoscaling_policy.proxy_requests.target_tracking_scaling_policy_configuration[0].predefined_metric_specification[0].predefined_metric_type == "ALBRequestCountPerTarget"
    error_message = "Request-count scaling policy missing."
  }
  assert {
    condition     = strcontains(aws_apigatewayv2_stage.api.access_log_settings[0].format, "$context.identity.sourceIp")
    error_message = "API access log must contain the source IP."
  }
  assert {
    condition     = alltrue([for r in aws_apigatewayv2_route.api : r.authorization_type == "CUSTOM"])
    error_message = "Every API route needs the authorizer."
  }
  assert {
    condition     = output.tencent_handoff.origin_pull_cidrs.count == 3
    error_message = "Handoff must report the CIDR count."
  }
}

run "quota_below_prefix_list_capacity_fails_at_plan" {
  command = plan
  override_data {
    target = data.aws_servicequotas_service_quota.sg_rules
    values = { value = 60 }
  }
  expect_failures = [aws_vpc_security_group_ingress_rule.edgeone]
}

run "more_cidrs_than_capacity_fails" {
  command = plan
  variables {
    edgeone_prefix_list_max_entries = 2
  }
  override_data {
    target = data.aws_servicequotas_service_quota.sg_rules
    values = { value = 200 }
  }
  expect_failures = [aws_ec2_managed_prefix_list.edgeone]
}

run "shared_secret_rejected" {
  command = plan
  variables {
    api_secret_arn = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:edgeone-origin/static-origin-token-AAAAAA"
  }
  expect_failures = [var.api_secret_arn]
}

run "unnormalized_or_wide_cidr_rejected" {
  command = plan
  variables {
    edgeone_ipv4_cidrs = ["43.0.0.5/24", "0.0.0.0/0"]
  }
  expect_failures = [var.edgeone_ipv4_cidrs]
}

run "header_injection_in_default_cache_control_rejected" {
  command = plan
  variables {
    default_cache_control = "public\nSet-Cookie: a=b"
  }
  expect_failures = [var.default_cache_control]
}

run "endpoint_must_cover_actual_task_routes" {
  command = plan
  override_data {
    target = data.aws_vpc_endpoint.s3
    values = { state = "available", route_table_ids = ["rtb-unrelated"] }
  }
  expect_failures = [check.s3_gateway_endpoint]
}
run "implicit_main_route_table_is_checked" {
  command = plan
  override_data {
    # Override every instance: no private subnet has an explicit association, so all fall back
    # to the main route table. (Instance keys are not accepted as override targets.)
    target = data.aws_route_tables.explicit_private
    values = { ids = [] }
  }
  override_data {
    target = data.aws_route_tables.main
    values = { ids = ["rtb-main-uncovered"] }
  }
  expect_failures = [check.s3_gateway_endpoint]
}
run "alarms_cover_lb_api_and_metric_heartbeat" {
  command = plan
  assert {
    condition     = aws_cloudwatch_metric_alarm.proxy_metrics_missing.treat_missing_data == "breaching"
    error_message = "A missing proxy metric stream must alarm."
  }
  assert {
    condition     = length(aws_cloudwatch_metric_alarm.alb) == 3 && aws_cloudwatch_metric_alarm.api_5xx.metric_name == "5xx"
    error_message = "ALB and API error alarms are required."
  }
}
run "least_privilege_and_no_decision_cache" {
  command = plan
  assert {
    condition     = jsondecode(aws_iam_role_policy.authorizer.policy).Statement[2].Resource == [aws_ec2_managed_prefix_list.edgeone.arn]
    error_message = "EC2 permission must be scoped to exactly the configured prefix list."
  }
  assert {
    condition     = aws_apigatewayv2_authorizer.origin.authorizer_result_ttl_in_seconds == 0
    error_message = "IP-dependent decisions must not be cached by token alone."
  }
}
