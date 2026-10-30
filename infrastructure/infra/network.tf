resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  vpc_id      = var.vpc_id
  description = "HTTPS only from approved EdgeOne origin-pull networks"
}
# One managed prefix list holds the EdgeOne origin-pull ranges (single source of truth; the
# API authorizer reads this list at runtime). Entry changes are applied to the
# list in place, without replacing security group rules.
resource "aws_ec2_managed_prefix_list" "edgeone" {
  name           = "${var.name}-edgeone-origin-pull"
  address_family = "IPv4"
  max_entries    = var.edgeone_prefix_list_max_entries
  dynamic "entry" {
    for_each = var.edgeone_ipv4_cidrs
    content {
      cidr        = entry.value
      description = "EdgeOne origin-pull"
    }
  }
  lifecycle {
    precondition {
      condition     = length(var.edgeone_ipv4_cidrs) <= var.edgeone_prefix_list_max_entries
      error_message = "edgeone_ipv4_cidrs has more entries than edgeone_prefix_list_max_entries."
    }
  }
}

# A rule that references a prefix list consumes max_entries rules of the security group
# quota (default 60). Fail at plan time instead of half-way through apply.
data "aws_servicequotas_service_quota" "sg_rules" {
  service_code = "vpc"
  quota_code   = "L-0EA8095F" # Inbound or outbound rules per security group
}

resource "aws_vpc_security_group_ingress_rule" "edgeone" {
  security_group_id = aws_security_group.alb.id
  prefix_list_id    = aws_ec2_managed_prefix_list.edgeone.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  lifecycle {
    precondition {
      condition     = data.aws_servicequotas_service_quota.sg_rules.value >= var.edgeone_prefix_list_max_entries
      error_message = "Service Quotas: 'Inbound or outbound rules per security group' (vpc/L-0EA8095F) is ${data.aws_servicequotas_service_quota.sg_rules.value}, below edgeone_prefix_list_max_entries=${var.edgeone_prefix_list_max_entries}. Request an increase first (rules x security groups per ENI must stay <= 1000)."
    }
  }
}
resource "aws_security_group" "proxy" {
  name_prefix = "${var.name}-proxy-"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "proxy" {
  security_group_id            = aws_security_group.proxy.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
resource "aws_vpc_security_group_egress_rule" "alb" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.proxy.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}
resource "aws_vpc_security_group_egress_rule" "proxy_https" {
  security_group_id = aws_security_group.proxy.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_security_group" "vpc_link" {
  name_prefix = "${var.name}-link-"
  vpc_id      = var.vpc_id
}
resource "aws_security_group" "nlb" {
  name_prefix = "${var.name}-nlb-"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_egress_rule" "link" {
  security_group_id            = aws_security_group.vpc_link.id
  referenced_security_group_id = aws_security_group.nlb.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
resource "aws_vpc_security_group_ingress_rule" "nlb" {
  security_group_id            = aws_security_group.nlb.id
  referenced_security_group_id = aws_security_group.vpc_link.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
resource "aws_vpc_security_group_egress_rule" "nlb" {
  security_group_id            = aws_security_group.nlb.id
  referenced_security_group_id = var.backend_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}
resource "aws_vpc_security_group_ingress_rule" "backend" {
  security_group_id            = var.backend_security_group_id
  referenced_security_group_id = aws_security_group.nlb.id
  ip_protocol                  = "tcp"
  from_port                    = var.backend_port
  to_port                      = var.backend_port
}
