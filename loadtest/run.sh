#!/usr/bin/env sh
# One load-test run against a fresh stack:
#   ./loadtest/run.sh baseline
#   PEAK_LOOKUPS=3000 ./loadtest/run.sh bigger
# Writes to loadtest/results/: <name>.json (k6 summary), <name>.txt (k6 text summary),
# <name>.stats.txt (docker stats every 5 s), <name>.metrics.txt (Hikari gauges at peak),
# <name>.pgwait.txt (Postgres wait events at peak)
# and <name>.verify.txt (post-run invariant checks).
set -e
NAME=${1:?usage: run.sh <name>}
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
hostpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
OUT=loadtest/results
mkdir -p "$OUT"
COMPOSE="docker compose -f docker-compose.yml -f loadtest/compose.loadtest.yml"

$COMPOSE down -v >/dev/null 2>&1
$COMPOSE up --build -d >/dev/null 2>&1
until curl -sf localhost:8082/actuator/health >/dev/null; do sleep 2; done
sleep 5

# Container CPU/memory every 5 s while k6 runs (docker stats: 100% = one core).
(for i in $(seq 1 20); do
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' orbitlink-api-1 orbitlink-postgres-1 orbitlink-redis-1
  echo "--"; sleep 5
done) > "$OUT/$NAME.stats.txt" &
STATS=$!

# Connection pool gauges sampled in the middle of the peak (the burst runs from 30 s to 90 s).
(sleep 60; for m in hikaricp.connections.active hikaricp.connections.pending hikaricp.connections.max; do
  printf '%s ' "$m"; curl -s "localhost:8082/actuator/metrics/$m" | sed 's/.*"value":\([0-9.E]*\).*/\1/'; echo
done) > "$OUT/$NAME.metrics.txt" &

# What Postgres backends are doing during the peak: 10 samples of pg_stat_activity, one per second.
(sleep 55; for i in $(seq 1 10); do
  $COMPOSE exec -T postgres psql -U orbitlink -d orbitlink -Atc     "SELECT coalesce(wait_event_type,'CPU') || ':' || coalesce(wait_event,'running') || ' ' || state, count(*)
     FROM pg_stat_activity WHERE datname = 'orbitlink' AND pid <> pg_backend_pid() GROUP BY 1 ORDER BY 2 DESC"
  echo "--"; sleep 1
done) > "$OUT/$NAME.pgwait.txt" &

docker run --rm --network orbitlink_default \
  -v "$(hostpath $OUT)":/results -v "$(hostpath loadtest)":/scripts \
  -e SUMMARY=/results/$NAME.json -e PEAK_LOOKUPS -e PEAK_ORDERS \
  grafana/k6:latest run --quiet /scripts/spike.js | tee "$OUT/$NAME.txt" || true
wait $STATS || true

$COMPOSE exec -T postgres psql -U orbitlink -d orbitlink -f - < loadtest/verify.sql > "$OUT/$NAME.verify.txt"
cat "$OUT/$NAME.verify.txt"
