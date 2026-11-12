#!/usr/bin/env bash
# Scan dependencies for known vulnerabilities (osv.dev) with a pinned, checksum-verified osv-scanner.
# Fails (non-zero exit) when a vulnerability is found.
#   scripts/osv-scan.sh                      # pom.xml and any other manifests in the repository
#   scripts/osv-scan.sh -L target/bom.json   # a CycloneDX SBOM (what ships in the plugin jar)
set -euo pipefail

OSV_SCANNER_VERSION=v2.7.1
OSV_SCANNER_SHA256=fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8 # osv-scanner_linux_amd64

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ "$(uname -sm)" == "Linux x86_64" ]]; then
  dir="${RUNNER_TEMP:-${XDG_CACHE_HOME:-$HOME/.cache}}/osv-scanner-$OSV_SCANNER_VERSION"
  osv="$dir/osv-scanner"
  if ! echo "$OSV_SCANNER_SHA256  $osv" | sha256sum -c --status - 2>/dev/null; then
    mkdir -p "$dir"
    curl -fsSLo "$osv.download" \
      "https://github.com/google/osv-scanner/releases/download/$OSV_SCANNER_VERSION/osv-scanner_linux_amd64"
    echo "$OSV_SCANNER_SHA256  $osv.download" | sha256sum -c -
    chmod 0755 "$osv.download"
    mv "$osv.download" "$osv"
  fi
elif command -v osv-scanner >/dev/null; then
  osv="osv-scanner" # other platforms: use the installed one, unverified
else
  echo "osv-scanner: no pinned binary for $(uname -sm); install osv-scanner $OSV_SCANNER_VERSION" >&2
  exit 1
fi

"$osv" --version | head -n 1
if (($# == 0)); then
  set -- --recursive .
fi
exec "$osv" scan source "$@"
