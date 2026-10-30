resource "aws_s3_bucket" "assets" {
  count         = var.create_bucket ? 1 : 0
  bucket        = var.bucket_name
  force_destroy = false
  lifecycle { prevent_destroy = true }
}
resource "aws_s3_bucket_public_access_block" "assets" {
  count                   = var.create_bucket ? 1 : 0
  bucket                  = aws_s3_bucket.assets[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "assets" {
  count  = var.create_bucket ? 1 : 0
  bucket = aws_s3_bucket.assets[0].id
  rule { object_ownership = "BucketOwnerEnforced" }
}
resource "aws_s3_bucket_versioning" "assets" {
  count  = var.create_bucket ? 1 : 0
  bucket = aws_s3_bucket.assets[0].id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  count  = var.create_bucket ? 1 : 0
  bucket = aws_s3_bucket.assets[0].id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_policy" "assets" {
  count  = var.create_bucket ? 1 : 0
  bucket = aws_s3_bucket.assets[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport", Effect = "Deny", Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.assets[0].arn, "${aws_s3_bucket.assets[0].arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}
locals {
  bucket_arn = "arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}"
  # Each component can read only its own token secret.
  secret_kms_policy = var.secret_kms_key_arn == null ? [] : [
    { Effect = "Allow", Action = ["kms:Decrypt"], Resource = [var.secret_kms_key_arn] }
  ]
  static_secret_policy = concat([
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.static_secret_arn] }
  ], local.secret_kms_policy)
  api_secret_policy = concat([
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.api_secret_arn] }
  ], local.secret_kms_policy)
}
