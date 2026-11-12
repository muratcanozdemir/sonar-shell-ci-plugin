#!/usr/bin/env bash
# Analyze a project with the local SonarQube container, using a scanner image that bundles ShellCheck.
#
# Usage: SONAR_TOKEN=... scripts/scan.sh [project-dir] [extra sonar-scanner args...]
#   project-dir defaults to this repository. SONAR_HOST_URL defaults to the local container.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_dir="$(cd "${1:-$repo_root}" && pwd)"
shift || true

: "${SONAR_TOKEN:?Set SONAR_TOKEN to a SonarQube analysis token}"
host_url="${SONAR_HOST_URL:-http://localhost:9000}"
image="shell-ci-scanner:local"

# Always build: cached layers make this instant, and edits to the Dockerfile (e.g. a ShellCheck bump) take effect.
docker build -q -t "$image" -f "$repo_root/docker/scanner.Dockerfile" "$repo_root/docker" >/dev/null

docker run --rm \
  --network host \
  --user "$(id -u):$(id -g)" \
  -e SONAR_HOST_URL="$host_url" \
  -e SONAR_TOKEN \
  -v "$project_dir:/usr/src" \
  "$image" "$@"
