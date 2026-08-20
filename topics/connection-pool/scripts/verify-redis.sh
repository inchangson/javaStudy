#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
container_id=$(docker run -d --rm -p 127.0.0.1::6379 redis:7.4.2-alpine redis-server --save '' --appendonly no)
trap 'docker rm -f "$container_id" >/dev/null' EXIT
for attempt in $(seq 1 50); do
    if docker exec "$container_id" redis-cli ping 2>/dev/null | rg -q '^PONG'; then break; fi
    sleep 0.1
done
docker exec "$container_id" redis-cli ping
redis_port=$(docker port "$container_id" 6379/tcp | cut -d: -f2)
export REDIS_URI="redis://127.0.0.1:${redis_port}/0"
cd "$repo_root"
./gradlew :topics:connection-pool:redisTest :topics:connection-pool:runDemo -Pdemo=poolstudy.lettuce.LettuceDemo
