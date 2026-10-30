#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s tests -v
python3 scripts/build_authorizer.py
mvn -B -f operator-tools/pom.xml verify
terraform fmt -check -recursive
terraform -chdir=bootstrap init -backend=false
terraform -chdir=bootstrap validate
terraform -chdir=infra init -backend=false
terraform -chdir=infra validate
terraform -chdir=infra test
terraform -chdir=examples/direct-s3-iam init -backend=false
terraform -chdir=examples/direct-s3-iam validate
terraform -chdir=examples/direct-s3-iam test
