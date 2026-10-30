variable "region" {
  type        = string
  description = "AWS region."
  default     = "eu-central-1"
}

variable "name" {
  type        = string
  description = "Resource name prefix (lowercase, <= 20 characters)."
  default     = "edgeone-origin"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,19}$", var.name))
    error_message = "Use 2-20 lowercase letters, digits or hyphens."
  }
}

variable "vpc_id" {
  type        = string
  description = "Existing VPC ID."
}

variable "public_subnet_ids" {
  type        = list(string)
  description = "At least two public subnets in distinct AZs, with IGW routing."
  validation {
    condition     = length(distinct(var.public_subnet_ids)) >= 2
    error_message = "Provide at least two subnets in distinct AZs."
  }
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "At least two private subnets in distinct AZs; NAT or required VPC endpoints."
  validation {
    condition     = length(distinct(var.private_subnet_ids)) >= 2
    error_message = "Provide at least two subnets in distinct AZs."
  }
}

variable "route53_zone_id" {
  type        = string
  description = "Public Route53 hosted zone for the static origin."
}

variable "static_origin_hostname" {
  type        = string
  description = "Dedicated static origin DNS name, not the viewer hostname."
}

variable "static_certificate_arn" {
  type        = string
  description = "Issued ACM certificate in this region covering static_origin_hostname."
}

variable "proxy_image" {
  type        = string
  description = "Private ECR image URI pinned with @sha256 digest."
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.proxy_image))
    error_message = "Pin the container image by sha256 digest."
  }
}

variable "bucket_name" {
  type        = string
  description = "New or existing S3 bucket name."
}

variable "create_bucket" {
  type        = bool
  description = "Create bucket; false uses an existing bucket without modifying its controls."
  default     = true
}

variable "object_prefix" {
  type        = string
  description = "Publishable S3 prefix, ending in /; empty permits all object keys."
  default     = "public/"
  validation {
    condition     = var.object_prefix == "" || (endswith(var.object_prefix, "/") && !startswith(var.object_prefix, "/") && length(regexall("[?*]", var.object_prefix)) == 0)
    error_message = "Prefix must end with /, not start with /, and contain no IAM wildcard."
  }
}

variable "bucket_kms_key_arn" {
  type        = string
  description = "Existing bucket CMK ARN, if SSE-KMS is used."
  default     = null
}

variable "backend_ips" {
  type        = set(string)
  description = "Existing application private IPv4 targets reachable from the NLB."
  validation {
    condition     = length(var.backend_ips) > 0
    error_message = "Supply at least one reachable backend target."
  }
}

variable "backend_port" {
  type        = number
  description = "HTTP port of backend application."
  default     = 8080
}

variable "backend_security_group_id" {
  type        = string
  description = "Existing backend SG. Adds ingress from NLB SG only."
}

variable "backend_health_path" {
  type        = string
  description = "Backend HTTP health check URL."
  default     = "/health"
}

variable "api_nlb_certificate_arn" {
  type        = string
  description = "Issued regional ACM certificate for NLB TLS listener."
}

variable "api_nlb_tls_hostname" {
  type        = string
  description = "Hostname in NLB certificate, verified by API Gateway."
}

variable "edgeone_ipv4_cidrs" {
  type        = set(string)
  description = "EdgeOne origin-pull IPv4 CIDRs (Origin Protection). Generate edgeone_cidrs.auto.tfvars.json with origin-tools cidrs; used by the ALB prefix list and the API authorizer."
  validation {
    condition = length(var.edgeone_ipv4_cidrs) > 0 && alltrue([
      for c in var.edgeone_ipv4_cidrs : can(cidrnetmask(c)) && can(regex("/([89]|[12][0-9]|3[0-2])$", c)) && try(cidrhost(c, 0) == split("/", c)[0], false)
    ])
    error_message = "Provide non-empty, normalized IPv4 CIDRs (network address, prefix /8 or longer; never 0.0.0.0/0)."
  }
}

variable "edgeone_prefix_list_max_entries" {
  type        = number
  description = "Capacity of the EdgeOne prefix list. Counts fully against the 'rules per security group' quota (default 60)."
  default     = 200
  validation {
    condition     = var.edgeone_prefix_list_max_entries >= 1 && var.edgeone_prefix_list_max_entries <= 1000
    error_message = "Use 1-1000 entries."
  }
}

variable "static_secret_arn" {
  type        = string
  description = "Secrets Manager secret ARN with {\"tokens\": [...]} for the static origin (read only by the proxy)."
}

variable "api_secret_arn" {
  type        = string
  description = "Secrets Manager secret ARN with {\"tokens\": [...]} for the API origin (read only by the authorizer)."
  validation {
    condition     = var.api_secret_arn != var.static_secret_arn
    error_message = "Static and API origins must use different secrets."
  }
}

variable "default_cache_control" {
  type        = string
  description = "Cache-Control sent for objects WITHOUT Cache-Control metadata. Keep no-store unless the bucket audit (origin-tools audit-cache-control) shows it is safe to cache them."
  default     = "no-store"
  validation {
    condition     = can(regex("^[A-Za-z0-9=,_ -]{1,256}$", var.default_cache_control))
    error_message = "Use a plain Cache-Control value, e.g. no-store or public, max-age=300."
  }
}

variable "secret_kms_key_arn" {
  type        = string
  description = "CMK ARN if origin secret uses a customer-managed KMS key."
  default     = null
}

variable "proxy_min_tasks" {
  type        = number
  description = "Minimum Fargate tasks, >=2."
  default     = 2
  validation {
    condition     = var.proxy_min_tasks >= 2
    error_message = "Use at least two tasks."
  }
}

variable "proxy_max_tasks" {
  type        = number
  description = "Maximum Fargate tasks."
  default     = 6
}

variable "proxy_max_concurrency" {
  type        = number
  description = "Maximum admitted requests per task and S3 pool size; no waiting queue. Excess requests return 503."
  default     = 256
  validation {
    condition     = var.proxy_max_concurrency >= 16 && var.proxy_max_concurrency <= 4096
    error_message = "Use 16-4096."
  }
}

variable "proxy_target_requests_per_minute" {
  type        = number
  description = "Target ALBRequestCountPerTarget (requests per task per minute). Set from a load test with real object sizes."
  default     = 1500
}

# No defaults on purpose: the stage throttle is ONE shared bucket for all viewers (every
# request arrives from EdgeOne), so it must be sized from current API traffic. Per-client
# limits belong in EdgeOne (see docs/HANDOFF_TENCENT.md).
variable "api_rate_limit" {
  type        = number
  description = "API stage steady-state limit, requests/second, shared by all clients."
}

variable "api_burst_limit" {
  type        = number
  description = "API stage burst limit, shared by all clients."
}

variable "authorizer_package" {
  type        = string
  description = "Path to Python authorizer ZIP; default ../build/authorizer.zip (run scripts/build_authorizer.py first)."
  default     = null
}

variable "authorizer_memory_mb" {
  type        = number
  description = "Authorizer Lambda memory (also scales CPU)."
  default     = 256
  validation {
    condition     = var.authorizer_memory_mb >= 256 && var.authorizer_memory_mb <= 10240
    error_message = "Use 256-10240 MB."
  }
}

variable "authorizer_cidr_max_stale_seconds" {
  type    = number
  default = 3300
  validation {
    condition     = var.authorizer_cidr_max_stale_seconds >= 0 && var.authorizer_cidr_max_stale_seconds <= 3300
    error_message = "CIDR stale allowance must be between 0 and 3300 seconds."
  }
}
variable "authorizer_token_max_stale_seconds" {
  type    = number
  default = 60
  validation {
    condition     = var.authorizer_token_max_stale_seconds >= 0 && var.authorizer_token_max_stale_seconds <= 60
    error_message = "Token stale allowance must be between 0 and 60 seconds."
  }
}
variable "alarm_action_arns" {
  type        = list(string)
  default     = []
  description = "SNS topic ARNs receiving alarms; supply monitored destinations before production."
}
