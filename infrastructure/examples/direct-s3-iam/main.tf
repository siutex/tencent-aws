# OPTIONAL separate root: deploy only after explicitly choosing direct S3 instead of proxy.
# This creates an IAM identity/policy, NEVER access keys or a console password.
terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
provider "aws" { region = var.region }
data "aws_partition" "current" {}
variable "region" { type = string }
variable "user_name" { type = string }
variable "bucket_name" { type = string }
variable "object_prefix" {
  type    = string
  default = "public/"
  validation {
    condition     = length(var.object_prefix) > 0 && endswith(var.object_prefix, "/") && length(regexall("[?*]", var.object_prefix)) == 0 && !startswith(var.object_prefix, "/")
    error_message = "A non-empty prefix ending with / and without IAM wildcards is required."
  }
}
variable "origin_pull_cidrs" {
  type = set(string)
  validation {
    condition     = length(var.origin_pull_cidrs) > 0 && alltrue([for c in var.origin_pull_cidrs : can(cidrnetmask(c)) && can(regex("/([89]|[12][0-9]|3[0-2])$", c))])
    error_message = "Provide explicit EdgeOne origin-pull IPv4 CIDRs /8 or narrower."
  }
}
variable "kms_key_arn" {
  type    = string
  default = null
}
resource "aws_iam_user" "edgeone" {
  name          = var.user_name
  force_destroy = false
  tags          = { Purpose = "EdgeOne-direct-S3", RotationMaxDays = "90" }
}
locals {
  read_only_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([{
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}/${var.object_prefix}*"]
      Condition = {
        Bool      = { "aws:SecureTransport" = "true" }
        IpAddress = { "aws:SourceIp" = sort(tolist(var.origin_pull_cidrs)) }
      }
      }], var.kms_key_arn == null ? [] : [{
      Effect   = "Allow"
      Action   = ["kms:Decrypt"]
      Resource = [var.kms_key_arn]
      Condition = {
        StringEquals = { "kms:ViaService" = "s3.${var.region}.${data.aws_partition.current.dns_suffix}" }
      }
    }])
  })
}
# Customer managed policy, not an inline user policy: inline policies of a user are limited to
# 2,048 characters in total, which ~100 origin-pull CIDRs already exceed. Managed: 6,144.
resource "aws_iam_policy" "read_only" {
  name        = "${var.user_name}-edgeone-prefix-read-only"
  description = "EdgeOne direct S3 origin: GetObject on one prefix from origin-pull CIDRs only."
  policy      = local.read_only_policy
  lifecycle {
    precondition {
      condition     = length(local.read_only_policy) <= 6144
      error_message = "Policy is ${length(local.read_only_policy)} characters; IAM managed policies allow 6,144. Merge CIDRs (origin-tools cidrs collapses adjacent ranges)."
    }
  }
}
resource "aws_iam_user_policy_attachment" "read_only" {
  user       = aws_iam_user.edgeone.name
  policy_arn = aws_iam_policy.read_only.arn
}
output "iam_user_name" { value = aws_iam_user.edgeone.name }
output "iam_user_arn" { value = aws_iam_user.edgeone.arn }
