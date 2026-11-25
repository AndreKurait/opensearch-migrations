#!/usr/bin/env bash
set -euo pipefail
work=$(mktemp -d)
trap 'gpgconf --kill gpg-agent; rm -rf "$work"' EXIT
export GNUPGHOME="$work/gnupg"
install -d -m 0700 "$GNUPGHOME"
cp opensearch-migrations-source.tar.gz "$work/"
python3 .github/scripts/release-bundle.py unpack opensearch-migrations-build.tar.gz "$work/repository" "0.$RELEASE_TAG"
gpg --batch --passphrase '' --quick-gen-key 'Dry Run <release@invalid.example>' ed25519 sign 1d
# Substitute only the external OpenSearch signer. Exercise our signing, armor, verification and ZIP paths.
mkdir "$work/signer"
cat > "$work/signer/opensearch-signer-client" <<'SIGNER'
#!/usr/bin/env bash
set -euo pipefail
while getopts 'i:o:p:e:r:' opt; do
    case $opt in i) input=$OPTARG ;; o) output=$OPTARG ;; esac
done
gpg --batch --detach-sign --output "$output" "$input"
SIGNER
chmod +x "$work/signer/opensearch-signer-client"
.github/scripts/release-sign.sh "$work" "$work/signer"
python3 .github/scripts/release-bundle.py bundle "$work/repository" "$work/central.zip"
unzip -tq "$work/central.zip"
