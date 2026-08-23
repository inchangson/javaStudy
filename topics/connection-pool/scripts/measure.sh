#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$repo_root"
result_path="${1:-topics/connection-pool/build/results/sizing.csv}"
mkdir -p "$(dirname "$result_path")"
./gradlew -q :topics:connection-pool:runDemo -Pdemo=poolstudy.sizing.SizingDemo > "$result_path"
printf 'CSV: %s\n' "$result_path"
