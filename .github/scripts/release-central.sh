#!/usr/bin/env bash
set -euo pipefail
: "${CENTRAL_USER:?}" "${CENTRAL_TOKEN:?}" "${RELEASE_TAG:?}"
endpoint=https://central.sonatype.com/api/v1/publisher
token=$(printf '%s:%s' "$CENTRAL_USER" "$CENTRAL_TOKEN" | base64 -w0)
echo "::add-mask::$token"
auth=(--header "Authorization: Bearer $token")
# Re-run a failed status check using its deployment ID to avoid duplicating a successful upload.
deployment=${CENTRAL_DEPLOYMENT_ID:-}
if [[ -z $deployment ]]; then
    deployment=$(curl --fail-with-body --silent --show-error "${auth[@]}" --form "bundle=@$1" \
        "$endpoint/upload?name=opensearch-migrations-$RELEASE_TAG&publishingType=AUTOMATIC")
fi
[[ $deployment =~ ^[[:xdigit:]]{8}(-[[:xdigit:]]{4}){3}-[[:xdigit:]]{12}$ ]]
echo "Maven Central deployment $deployment" | tee -a "$GITHUB_STEP_SUMMARY"
for ((attempt=1; attempt<=180; attempt++)); do
    status=$(curl -fsS --retry 3 --request POST "${auth[@]}" "$endpoint/status?id=$deployment")
    state=$(jq -er '.deploymentState' <<<"$status")
    echo "$deployment $state"
    case $state in
        PUBLISHED) exit 0 ;;
        FAILED) jq '.errors' <<<"$status"; exit 1 ;;
    esac
    sleep 20
done
echo "Timed out waiting for Maven Central deployment $deployment" >&2
exit 1
