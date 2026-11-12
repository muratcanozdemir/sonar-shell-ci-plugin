#!/usr/bin/env bash
# Prepare the sample projects on a SonarQube instance:
#
# - the "Shell & CI samples" quality profile for CI Pipelines: inherits "Shell & CI way" and activates every CI rule,
#   so rules that are off by default (CheckoutPersistCredentials) are reported on their examples too;
# - the "Shell & CI samples" quality gate, so that the samples fail on purpose. The default gate (Sonar way) only checks
#   new code, and the deliberately vulnerable samples never change: they would always pass. This gate checks overall
#   code instead.
#
# Re-run it safely at any time; it only adds what is missing.
#
# Needs a token of a user allowed to administer quality gates and projects (e.g. admin), read from
# $SONAR_ADMIN_TOKEN or .sonarqube/admin-token (gitignored). The analysis token in .sonarqube/token is not enough.
#
# Usage: scripts/setup-samples.sh, then scripts/scan-samples.sh to analyze the samples with them.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
host_url="${SONAR_HOST_URL:-http://localhost:9000}"
gate="Shell & CI samples"
profile="Shell & CI samples"

token="${SONAR_ADMIN_TOKEN:-}"
if [[ -z "$token" && -r "$repo_root/.sonarqube/admin-token" ]]; then
  token="$(tr -d '[:space:]' < "$repo_root/.sonarqube/admin-token")"
fi
if [[ -z "$token" ]]; then
  echo "Set SONAR_ADMIN_TOKEN or write an admin user token to .sonarqube/admin-token" >&2
  exit 1
fi

# api METHOD PATH [curl args...]: calls the Web API, prints the response, fails with the server's message on error.
api() {
  local method="$1" path="$2" response status
  shift 2
  response="$(curl -sS -X "$method" -u "$token:" -w '\n%{http_code}' "$host_url/api/$path" "$@")"
  status="${response##*$'\n'}"
  response="${response%$'\n'*}"
  if [[ "$status" != 2* ]]; then
    echo "$method api/$path failed with HTTP $status: $response" >&2
    return 1
  fi
  printf '%s' "$response"
}

if ! api GET qualitygates/show --get --data-urlencode "name=$gate" >/dev/null 2>&1; then
  api POST qualitygates/create --data-urlencode "name=$gate" >/dev/null
  echo "Created quality gate \"$gate\""
fi

existing="$(api GET qualitygates/show --get --data-urlencode "name=$gate" \
  | python3 -c 'import json, sys; print(" ".join(c["metric"] for c in json.load(sys.stdin)["conditions"]))')"

# metric operator threshold: overall-code conditions (MQR mode metrics). Ratings: 1 = A ... 5 = E.
while read -r metric op error; do
  if [[ " $existing " == *" $metric "* ]]; then
    continue
  fi
  api POST qualitygates/create_condition --data-urlencode "gateName=$gate" \
    -d "metric=$metric" -d "op=$op" -d "error=$error" >/dev/null
  echo "Added condition $metric $op $error"
done <<'CONDITIONS'
software_quality_security_issues GT 0
security_hotspots_reviewed LT 100
software_quality_reliability_rating GT 1
CONDITIONS

# CI Pipelines profile: every rule of the "ci" repository, on top of the built-in profile.
profile_key() {
  api GET qualityprofiles/search --get -d language=ci --data-urlencode "qualityProfile=$profile" \
    | python3 -c 'import json, sys; p = json.load(sys.stdin)["profiles"]; print(p[0]["key"] if p else "")'
}
key="$(profile_key 2>/dev/null || true)"
if [[ -z "$key" ]]; then
  api POST qualityprofiles/create -d language=ci --data-urlencode "name=$profile" >/dev/null
  api POST qualityprofiles/change_parent -d language=ci --data-urlencode "qualityProfile=$profile" \
    --data-urlencode "parentQualityProfile=Shell & CI way" >/dev/null
  key="$(profile_key)"
  echo "Created quality profile \"$profile\" (CI Pipelines)"
fi
activated="$(api POST qualityprofiles/activate_rules -d "targetKey=$key" -d repositories=ci -d "qprofile=$key" \
  -d activation=false | python3 -c 'import json, sys; print(json.load(sys.stdin)["succeeded"])')"
if [[ "$activated" != 0 ]]; then
  echo "Activated $activated rule(s) in \"$profile\""
fi

for properties in "$repo_root"/samples/*/sonar-project.properties; do
  project="$(sed -n 's/^sonar\.projectKey=//p' "$properties")"
  if api POST qualitygates/select --data-urlencode "gateName=$gate" -d "projectKey=$project" >/dev/null 2>&1; then
    api POST qualityprofiles/add_project -d language=ci --data-urlencode "qualityProfile=$profile" \
      -d "project=$project" >/dev/null
    echo "Assigned the \"$gate\" gate and CI profile to $project"
  else
    echo "Skipped $project: not found on $host_url; analyze it first with scripts/scan-samples.sh" >&2
  fi
done

echo "Done. Run scripts/scan-samples.sh so the projects are analyzed with the profile and evaluated against the gate."
