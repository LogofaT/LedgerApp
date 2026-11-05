#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
project="ledger-verify-$(date +%s)-$$"
trap 'docker compose -p "$project" down -v --remove-orphans' EXIT
docker compose -p "$project" up --quiet-build --build -d mysql rabbitmq app1 app2
# The preceding up owns dependency startup; run must not recreate live databases/brokers.
docker compose -p "$project" --profile test run --no-deps --rm integration
