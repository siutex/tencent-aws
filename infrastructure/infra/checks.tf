# Resolve explicit subnet associations, falling back to the VPC main route table.
# Checking merely that an endpoint has some route table is insufficient.
data "aws_route_tables" "explicit_private" {
  for_each = toset(var.private_subnet_ids)
  vpc_id   = var.vpc_id
  filter {
    name   = "association.subnet-id"
    values = [each.value]
  }
}
data "aws_route_tables" "main" {
  vpc_id = var.vpc_id
  filter {
    name   = "association.main"
    values = ["true"]
  }
}
locals {
  private_route_table_ids = toset([
    for subnet in var.private_subnet_ids :
    length(data.aws_route_tables.explicit_private[subnet].ids) > 0 ?
    one(data.aws_route_tables.explicit_private[subnet].ids) : one(data.aws_route_tables.main.ids)
  ])
}
# Checks warn; the deployment acceptance procedure treats any warning as a blocker.
check "s3_gateway_endpoint" {
  data "aws_vpc_endpoint" "s3" {
    vpc_id       = var.vpc_id
    service_name = "${data.aws_partition.current.reverse_dns_prefix}.${var.region}.s3"
    filter {
      name   = "vpc-endpoint-type"
      values = ["Gateway"]
    }
  }
  assert {
    condition = data.aws_vpc_endpoint.s3.state == "available" && alltrue([
      for rt in local.private_route_table_ids : contains(data.aws_vpc_endpoint.s3.route_table_ids, rt)
    ])
    error_message = "S3 gateway endpoint must cover EVERY effective route table of ECS private subnets (including implicit main associations)."
  }
}
check "alarm_delivery" {
  assert {
    condition     = length(var.alarm_action_arns) > 0
    error_message = "Configure monitored alarm_action_arns before production acceptance."
  }
}
