#!/usr/bin/env bash
# Analyze every sample project under samples/ with scripts/scan.sh.
#
# Usage: SONAR_TOKEN=... scripts/scan-samples.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for sample in "$repo_root"/samples/*/; do
  echo "== ${sample#"$repo_root"/}"
  "$repo_root/scripts/scan.sh" "$sample" | grep -E 'ANALYSIS SUCCESSFUL|EXECUTION FAILURE|ERROR' || true
done
