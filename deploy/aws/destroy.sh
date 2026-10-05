#!/usr/bin/env bash
# Deletes the CloudFormation stack and (with --purge-images) the ECR repositories.
set -euo pipefail

REGION="${AWS_REGION:-us-east-1}"
STACK="${STACK_NAME:-telecom-bridge}"

echo "==> Deleting stack ${STACK} in ${REGION}"
aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
aws cloudformation wait stack-delete-complete --region "$REGION" --stack-name "$STACK"

if [[ "${1:-}" == "--purge-images" ]]; then
  for repo in "${STACK}/gateway" "${STACK}/simulator"; do
    echo "==> Deleting ECR repository ${repo}"
    aws ecr delete-repository --region "$REGION" --repository-name "$repo" --force >/dev/null || true
  done
fi
echo "==> Done"
