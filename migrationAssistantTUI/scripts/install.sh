#!/usr/bin/env bash
# install.sh — one-line installer for the Migration Assistant TUI.
#
# Usage:
#   curl -fsSL https://opensearch-migrations.io/tui/install.sh | bash
#
# Environment overrides:
#   MA_TUI_VERSION   release tag to install (default: latest)
#   MA_TUI_PREFIX    install dir; binary lands at $PREFIX/migration-assistant
#                    (default: /usr/local/bin if writable, else $HOME/.local/bin)
#   MA_TUI_REPO      GitHub repo (default: opensearch-project/opensearch-migrations)
#
# This installer is intentionally portable POSIX-ish bash. It downloads the
# release archive matching the host OS+arch, extracts the single binary, and
# moves it to $PREFIX. No package manager, no sudo unless the chosen $PREFIX
# requires it.

set -euo pipefail

REPO="${MA_TUI_REPO:-opensearch-project/opensearch-migrations}"
VERSION="${MA_TUI_VERSION:-latest}"
BINARY="migration-assistant"

log()  { printf '\033[1;34m[ma-tui]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ma-tui]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[ma-tui]\033[0m %s\n' "$*" >&2; exit 1; }

# --- detect OS + arch -------------------------------------------------------
uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_s" in
    Linux)   os="linux" ;;
    Darwin)  os="darwin" ;;
    *)       fail "unsupported OS: $uname_s" ;;
esac
case "$uname_m" in
    x86_64|amd64)   arch="amd64" ;;
    arm64|aarch64)  arch="arm64" ;;
    *)              fail "unsupported architecture: $uname_m" ;;
esac
log "detected platform: ${os}_${arch}"

# --- pick install prefix ----------------------------------------------------
if [ -n "${MA_TUI_PREFIX:-}" ]; then
    PREFIX="$MA_TUI_PREFIX"
elif [ -w /usr/local/bin ] 2>/dev/null; then
    PREFIX="/usr/local/bin"
elif [ "$(id -u)" = "0" ]; then
    PREFIX="/usr/local/bin"
else
    PREFIX="$HOME/.local/bin"
fi
mkdir -p "$PREFIX" || fail "cannot create install dir: $PREFIX"
log "install prefix: $PREFIX"

# --- resolve version --------------------------------------------------------
if [ "$VERSION" = "latest" ]; then
    log "resolving latest release tag"
    VERSION="$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
        | grep -oE '"tag_name"[^,]*' \
        | head -1 \
        | sed -E 's/.*"([^"]+)".*/\1/')"
    [ -n "$VERSION" ] || fail "could not resolve latest release tag"
fi
log "installing $BINARY $VERSION"

# --- download + extract -----------------------------------------------------
asset="migration-assistant_${VERSION#v}_${os}_${arch}.tar.gz"
url="https://github.com/${REPO}/releases/download/${VERSION}/${asset}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

log "downloading $url"
if ! curl -fsSL --retry 3 -o "$tmp/$asset" "$url"; then
    fail "download failed: $url"
fi

# Optional checksum verification (best-effort; skipped if checksums.txt absent)
checksum_url="https://github.com/${REPO}/releases/download/${VERSION}/checksums.txt"
if curl -fsSL --retry 2 -o "$tmp/checksums.txt" "$checksum_url" 2>/dev/null; then
    log "verifying checksum"
    expected="$(grep "  ${asset}\$" "$tmp/checksums.txt" | awk '{print $1}')"
    if [ -n "$expected" ]; then
        actual="$(sha256sum "$tmp/$asset" 2>/dev/null | awk '{print $1}' || \
                  shasum -a 256 "$tmp/$asset" | awk '{print $1}')"
        [ "$expected" = "$actual" ] || fail "checksum mismatch (expected $expected, got $actual)"
        log "checksum OK"
    else
        warn "checksum for $asset not found in checksums.txt; continuing without verification"
    fi
else
    warn "no checksums.txt published; skipping checksum verification"
fi

log "extracting"
tar -xzf "$tmp/$asset" -C "$tmp"
[ -f "$tmp/$BINARY" ] || fail "extracted archive does not contain $BINARY"

# --- install ----------------------------------------------------------------
chmod +x "$tmp/$BINARY"
mv "$tmp/$BINARY" "$PREFIX/$BINARY"
log "installed: $PREFIX/$BINARY"

case ":$PATH:" in
    *":$PREFIX:"*) : ;;
    *) warn "$PREFIX is not on \$PATH — add this to your shell profile:"
       warn "    export PATH=\"$PREFIX:\$PATH\"" ;;
esac

cat <<'EOF'

[ma-tui] Done. Run:

    migration-assistant

To migrate to the latest release later:

    curl -fsSL https://opensearch-migrations.io/tui/install.sh | bash

EOF
