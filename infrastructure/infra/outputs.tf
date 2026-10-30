output "tencent_handoff" {
  value = {
    static = { origin = var.static_origin_hostname, protocol = "HTTPS", port = 443, host_header = var.static_origin_hostname, sni = var.static_origin_hostname, header_name = "X-Origin-Verify", token = "static origin secret (confirm by fingerprint)", methods = ["GET", "HEAD"] }
    api    = { origin = trimprefix(aws_apigatewayv2_api.api.api_endpoint, "https://"), protocol = "HTTPS", port = 443, host_header = trimprefix(aws_apigatewayv2_api.api.api_endpoint, "https://"), sni = trimprefix(aws_apigatewayv2_api.api.api_endpoint, "https://"), paths = ["/api", "/api/*"], header_name = "X-Origin-Verify", token = "API origin secret (confirm by fingerprint)", cache = "BYPASS", per_client_rate_limit = "required in EdgeOne" }
    origin_pull_cidrs = {
      count = length(var.edgeone_ipv4_cidrs)
      # Lets both teams confirm they work from the same list without exchanging it again.
      sha256 = sha256(jsonencode(sort(tolist(var.edgeone_ipv4_cidrs))))
    }
  }
}
output "proxy_task_role_arn" { value = aws_iam_role.proxy.arn }
output "bucket_name" { value = var.bucket_name }
output "api_endpoint" { value = aws_apigatewayv2_api.api.api_endpoint }
output "nlb_arn" { value = aws_lb.api.arn }
output "edgeone_prefix_list_id" { value = aws_ec2_managed_prefix_list.edgeone.id }
