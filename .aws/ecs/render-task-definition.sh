#!/usr/bin/env bash
# Render the ECS task definition registered by Backend V2 ECS CD.
#
# Usage: PROD_ENV="$(...)" render-task-definition.sh <template> <container> <image> > next.json
#
# PROD_ENV holds one KEY=VALUE per line (GitHub Actions variable `PROD_ENV`).
# Blank lines and lines starting with '#' are ignored. Each key replaces the
# template value with the same name or is appended to the container environment.
# Values are plain text: secrets must stay in `secrets[].valueFrom` (SSM).
set -Eeuo pipefail

template="$1"
container="$2"
image="$3"

overrides='[]'
line_no=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_no=$((line_no + 1))
  line="${line%$'\r'}"
  [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue
  if [[ ! "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
    echo "::error::PROD_ENV line ${line_no} must be KEY=VALUE" >&2
    exit 1
  fi
  overrides="$(jq -c --arg n "${BASH_REMATCH[1]}" --arg v "${BASH_REMATCH[2]}" \
    '. + [{name: $n, value: $v}]' <<<"$overrides")"
done <<<"${PROD_ENV:-}"

duplicates="$(jq -r '[group_by(.name)[] | select(length > 1) | .[0].name] | join(", ")' <<<"$overrides")"
if [[ -n "$duplicates" ]]; then
  echo "::error::PROD_ENV has duplicate keys: ${duplicates}" >&2
  exit 1
fi

secret_conflicts="$(jq -r --arg name "$container" --argjson o "$overrides" '
  [.containerDefinitions[] | select(.name == $name) | (.secrets // [])[].name] as $s
  | [$o[].name | select(. as $n | $s | index($n))] | join(", ")' "$template")"
if [[ -n "$secret_conflicts" ]]; then
  echo "::error::PROD_ENV must not override secrets: ${secret_conflicts}" >&2
  exit 1
fi

jq --arg name "$container" --arg image "$image" --argjson o "$overrides" '
  ($o | map(.name)) as $keys
  | .containerDefinitions |= map(
      if .name == $name then
        .image = $image
        | .environment = ([(.environment // [])[] | select(.name as $n | $keys | index($n) | not)] + $o)
      else . end)' "$template"
