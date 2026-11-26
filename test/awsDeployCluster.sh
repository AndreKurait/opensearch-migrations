#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

# Parse CDK --outputs-file into cluster-details JSON.
# CDK outputs use export names: ClusterEndpoint-{stage}-{clusterId}, ClusterSubnets-{stage}-{clusterId},
# ClusterAccessSecurityGroupId-{stage}-{clusterId}. The outputs file keys have dashes stripped by CFN.
write_cluster_outputs() {
  local stage="$1"
  local outfile="$2"
  local cdk_outputs_file="$3"
  local provided_vpc_id="${4:-}"

  # cdk --outputs-file produces: {"StackName": {"OutputKey": "OutputValue", ...}}
  # Flatten to a single object of all outputs across stacks
  outputs=$(jq 'to_entries | map(.value) | add // {}' "$cdk_outputs_file")

  # CDK strips hyphens from stage name in CFN OutputKeys
  stage_nohyphen=$(echo "$stage" | tr -d '-')
  vpc_id="${provided_vpc_id:-$(echo "$outputs" | jq -r --arg key "VpcIdExport${stage_nohyphen}" '.[$key] // empty')}"

  tmpfile=$(mktemp)
  echo "{}" > "$tmpfile"

  # Process ClusterEndpoint outputs (managed domains)
  while read -r output_key; do
    cluster_id=$(echo "$output_key" | sed "s/^ClusterEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$outputs" | jq -r --arg k "$output_key" '.[$k]')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    sg=$(echo "$outputs" | jq -r --arg k "ClusterAccessSecurityGroupIdExport${stage_nohyphen}${cluster_id}" '.[$k] // empty')
    subnets=$(echo "$outputs" | jq -r --arg k "ClusterSubnets${stage_nohyphen}${cluster_id}" '.[$k] // empty')

    jq --arg id "$cluster_id" --arg vpc "${vpc_id:-}" --arg endpoint "$endpoint" \
       --arg sg "${sg:-}" --arg subnets "${subnets:-}" \
       '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $sg, subnetIds: $subnets}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -r 'keys[] | select(test("^ClusterEndpointExport"))')

  # Process CollectionEndpoint outputs (serverless)
  while read -r output_key; do
    cluster_id=$(echo "$output_key" | sed "s/^CollectionEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$outputs" | jq -r --arg k "$output_key" '.[$k]')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    jq --arg id "$cluster_id" --arg endpoint "$endpoint" \
       '. + {($id): {vpcId: "", endpoint: $endpoint, securityGroupId: "", subnetIds: ""}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -r 'keys[] | select(test("^CollectionEndpointExport"))')

  mv "$tmpfile" "$outfile"
  echo "Wrote outputs to $outfile"
}

script_abs_path=$(readlink -f "$0")
SCRIPT_REPO_PATH=$(dirname "$(dirname "$script_abs_path")")
ROOT_REPO_PATH="${MIGRATIONS_TEST_REPO_PATH:-$SCRIPT_REPO_PATH}"
CLUSTER_CDK_PATH="$ROOT_REPO_PATH/test/amazon-opensearch-service-sample-cdk"
CLUSTER_CDK_CONTEXT_FILE_PATH="$CLUSTER_CDK_PATH/cdk.context.json"

# Defaults
STAGE="aws-integ"
PROVIDED_CONTEXT_FILE_PATH=""
VPC_ID=""
DESTROY=false

# Argument parser
while [[ $# -gt 0 ]]; do
  case "$1" in
    --stage|-s)
      STAGE="$2"
      shift 2
      ;;
    --context-file|-c)
      PROVIDED_CONTEXT_FILE_PATH="$2"
      shift 2
      ;;
    --vpc-id)
      VPC_ID="$2"
      shift 2
      ;;
    --destroy)
      DESTROY=true
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "Options:"
      echo "  -s, --stage <val>              Stage name (default: $STAGE)"
      echo "  -c, --context-file <path>      Path to context file (REQUIRED)"
      echo "  --vpc-id <id>                  VPC ID to use in cluster outputs (overrides CDK output)"
      echo "  --destroy                      Destroy all stacks instead of deploying"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      exit 1
      ;;
  esac
done

# Require context file
if [[ -z "$PROVIDED_CONTEXT_FILE_PATH" ]]; then
  echo "Error: --context-file is required"
  echo "Run with --help for usage"
  exit 1
fi

SAMPLE_CDK_REPO="https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git"
SAMPLE_CDK_VERSION="v0.4.2"
SAMPLE_CDK_COMMIT="5f3e2b7d7459f7435bd9a1539ff7360663dded6f"
echo "Using sample CDK version: $SAMPLE_CDK_VERSION"
if [[ -n ${CI_DEPENDENCY_PROXY_URL:-} ]]; then
  archive=$(mktemp)
  trap 'rm -f "$archive"' EXIT
  curl -fsS "$CI_DEPENDENCY_PROXY_URL/sample-cdk/$SAMPLE_CDK_COMMIT.tar.gz" -o "$archive"
  echo "22467ad1d1f341918dfb98f36704bc3738c8a87c818c7decda4e2a18945af2b8  $archive" | sha256sum -c -
  mkdir -p "$CLUSTER_CDK_PATH"
  tar -xzf "$archive" --strip-components=1 -C "$CLUSTER_CDK_PATH"
  cd "$CLUSTER_CDK_PATH"
else
  if [[ ! -d "$CLUSTER_CDK_PATH" ]]; then
    git clone "$SAMPLE_CDK_REPO" "$CLUSTER_CDK_PATH"
  fi
  cd "$CLUSTER_CDK_PATH"
  git checkout -- .
  git fetch --tags
  git checkout --detach "$SAMPLE_CDK_COMMIT"
fi
npm ci
npm run build

cd ..
cp -f "$PROVIDED_CONTEXT_FILE_PATH" "$CLUSTER_CDK_CONTEXT_FILE_PATH"

# Wait for any leftover OpenSearch domains from a previous run to finish deleting.
# The CDK VPC-validation Lambda will reject deployment if a domain with the same name
# still exists in a different VPC (e.g. from a prior run whose cleanup is still in progress).
# Every cluster entry must set `clusterName` explicitly. This keeps the script
# independent of sample-cdk's internal default naming pattern, which is an
# implementation detail of the sample-cdk and not part of this script's contract.
while read -r entry; do
  cid=$(echo "$entry" | jq -r '.clusterId')
  domain_name=$(echo "$entry" | jq -r '.clusterName // empty')
  if [[ -z "$domain_name" ]]; then
    echo "ERROR: cluster '$cid' is missing 'clusterName' in the context file. Set 'clusterName' explicitly for each cluster entry."
    exit 1
  fi
  if aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
    if aws opensearch describe-domain --domain-name "$domain_name" \
         --query 'DomainStatus.Deleted' --output text 2>/dev/null | grep -qi true; then
      echo "Domain '$domain_name' is still deleting — waiting up to 30 minutes..."
      for i in $(seq 1 60); do
        if ! aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
          echo "Domain '$domain_name' has been fully deleted."
          break
        fi
        sleep 30
      done
      # Final check
      if aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
        echo "ERROR: Domain '$domain_name' still exists after waiting. Aborting."
        exit 1
      fi
    else
      echo "WARNING: Domain '$domain_name' exists and is NOT being deleted. CDK deploy may fail if VPC differs."
    fi
  fi
done < <(jq -c '.clusters[]' "$PROVIDED_CONTEXT_FILE_PATH")

# Delete any ROLLBACK_COMPLETE stacks from a prior failed deploy — CDK cannot update these.
stack_name="OpenSearch-${STAGE}-${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region)}}"
stack_status=$(aws cloudformation describe-stacks --stack-name "$stack_name" \
  --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)
if [[ "$stack_status" == "ROLLBACK_COMPLETE" ]]; then
  aws cloudformation delete-stack --stack-name "$stack_name"
  aws cloudformation wait stack-delete-complete --stack-name "$stack_name"
fi

cd amazon-opensearch-service-sample-cdk
cdk_options=()
if [[ -n "${CI_ROLE_BOUNDARY_ARN:-}" ]]; then
  cdk_options+=(--app "node \"$SCRIPT_REPO_PATH/.github/ci/aws/domains-app.cjs\"")
fi

if [[ "$DESTROY" == true ]]; then
  npx cdk destroy "*" --force "${cdk_options[@]}"
else
  CDK_OUTPUTS_FILE=$(mktemp)
  npx cdk deploy "*" --require-approval never --concurrency 3 --outputs-file "$CDK_OUTPUTS_FILE" "${cdk_options[@]}"

  CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
  mkdir -p "$(dirname "$CLUSTER_DETAILS_OUTPUT_FILE_PATH")"
  cd ..
  write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH" "$CDK_OUTPUTS_FILE" "$VPC_ID"
  rm -f "$CDK_OUTPUTS_FILE"
fi
