#!/usr/bin/env bash
# Builds both images for linux/arm64, pushes them to ECR and deploys the CloudFormation stack.
#
# Prerequisites: AWS CLI v2 with credentials (aws configure / SSO), Docker with buildx.
# Usage: deploy/aws/deploy.sh                    # region from AWS_REGION, default us-east-1
#        STACK_NAME=tb-dev ALLOWED_CIDR=1.2.3.4/32 deploy/aws/deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REGION="${AWS_REGION:-us-east-1}"
STACK="${STACK_NAME:-telecom-bridge}"
TAG="${IMAGE_TAG:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"
ALLOWED_CIDR="${ALLOWED_CIDR:-$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')/32}"

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
GATEWAY_REPO="${STACK}/gateway"
SIMULATOR_REPO="${STACK}/simulator"

echo "==> Account ${ACCOUNT}, region ${REGION}, stack ${STACK}, tag ${TAG}, ALB open to ${ALLOWED_CIDR}"

for repo in "$GATEWAY_REPO" "$SIMULATOR_REPO"; do
  if ! aws ecr describe-repositories --region "$REGION" --repository-names "$repo" >/dev/null 2>&1; then
    echo "==> Creating ECR repository ${repo}"
    aws ecr create-repository --region "$REGION" --repository-name "$repo" \
      --image-scanning-configuration scanOnPush=true >/dev/null
  fi
done

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

for target in gateway simulator; do
  image="${REGISTRY}/${STACK}/${target}:${TAG}"
  echo "==> Building and pushing ${image}"
  docker buildx build --platform linux/arm64 --target "$target" -t "$image" --push "$ROOT"
done

echo "==> Deploying CloudFormation stack ${STACK}"
aws cloudformation deploy \
  --region "$REGION" \
  --stack-name "$STACK" \
  --template-file "$ROOT/deploy/aws/telecom-bridge.yaml" \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    GatewayImage="${REGISTRY}/${GATEWAY_REPO}:${TAG}" \
    SimulatorImage="${REGISTRY}/${SIMULATOR_REPO}:${TAG}" \
    AllowedCidr="${ALLOWED_CIDR}"

ENDPOINT="$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
  --query "Stacks[0].Outputs[?OutputKey=='ChargeEndpoint'].OutputValue" --output text)"
echo "==> Charge endpoint: ${ENDPOINT}"

echo "==> Waiting for the gateway to report the Diameter peer OPEN"
for _ in $(seq 1 60); do
  if curl -fsS "${ENDPOINT%/api/v1/charge}/actuator/health/readiness" >/dev/null 2>&1; then break; fi
  sleep 5
done
curl -fsS -X POST "$ENDPOINT" -H 'Content-Type: application/json' \
  -d '{"msisdn":"919876543210","requestedUnits":{"time":60}}'
echo
