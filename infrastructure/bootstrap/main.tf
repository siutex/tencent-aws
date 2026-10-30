terraform {
  required_version = ">= 1.6.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
variable "region" {
  type    = string
  default = "eu-central-1"
}
variable "name" {
  type    = string
  default = "edgeone-origin"
}
provider "aws" { region = var.region }
resource "aws_ecr_repository" "proxy" {
  name                 = "${var.name}-proxy"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
}
# Separate secrets: each component reads only its own token, and a bad edit or rotation of
# one origin cannot take the other one down.
resource "aws_secretsmanager_secret" "origin" {
  for_each                = toset(["static", "api"])
  name                    = "${var.name}/${each.key}-origin-token"
  recovery_window_in_days = 30
  description             = "JSON {\"tokens\": [...]} for the ${each.key} origin. Values written by origin-tools tokens, never by Terraform."
}
output "repository_url" { value = aws_ecr_repository.proxy.repository_url }
output "static_secret_arn" { value = aws_secretsmanager_secret.origin["static"].arn }
output "api_secret_arn" { value = aws_secretsmanager_secret.origin["api"].arn }
