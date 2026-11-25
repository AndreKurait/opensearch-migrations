#!/usr/bin/env bash
set -euo pipefail

: "${STAGING_REGISTRY:?}" "${DESTINATION_REGISTRY:?}" "${RELEASE_TAG:?}"
# The dry run uses the same OCI registry on the VM loopback for both namespaces.
tls=()
if [[ $STAGING_REGISTRY == localhost:* && $DESTINATION_REGISTRY == localhost:* ]]; then
    tls=(--src-tls-verify=false --dest-tls-verify=false)
fi
manifest=$(mktemp)
trap 'rm -f "$manifest"' EXIT
for name in console traffic-replayer traffic-capture-proxy reindex-from-snapshot transformation-shim migration-companion; do
    image="opensearch-migrations-$name"
    src="$STAGING_REGISTRY/$image"
    dst="$DESTINATION_REGISTRY/$image"
    inspect=()
    [[ $src != localhost:* ]] || inspect=(--tls-verify=false)
    skopeo inspect "${inspect[@]}" --raw "docker://$src:$RELEASE_TAG" > "$manifest"
    jq -e '[.manifests[].platform | select(.os=="linux") | .architecture] | contains(["amd64","arm64"])' "$manifest" > /dev/null
    # Pin to the inspected index digest so both published tags reference identical manifests.
    digest=$(skopeo manifest-digest "$manifest")
    for tag in "$RELEASE_TAG" latest; do
        echo "Copying $src@$digest to $dst:$tag"
        skopeo copy --all --preserve-digests --retry-times 3 "${tls[@]}" "docker://$src@$digest" "docker://$dst:$tag"
    done
done
