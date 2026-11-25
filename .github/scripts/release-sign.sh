#!/usr/bin/env bash
set -euo pipefail
work=$1
cd "$2"
source="$work/opensearch-migrations-source.tar.gz"
# The source path historically used the default OpenSearch project key. Maven used the release key.
./opensearch-signer-client -i "$source" -o "$source.sig" -p pgp -e opensearch@amazon.com -r True
gpg --batch --verify "$source.sig" "$source"
while IFS= read -r -d '' artifact; do
    ./opensearch-signer-client -i "$artifact" -o "$artifact.sig" -p pgp -e release@opensearch.org -r False
    # The upstream signer emits binary OpenPGP. Central requires ASCII-armored detached signatures.
    gpg --batch --enarmor < "$artifact.sig" | sed 's/ARMORED FILE/SIGNATURE/g' > "$artifact.asc"
    rm "$artifact.sig"
done < <(find "$work/repository" -type f -print0)
