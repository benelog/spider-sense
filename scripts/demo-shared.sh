#!/usr/bin/env bash
# The shared layout: one standalone Spider Sense on :4000, with silk-bookstore on
# :8081, spring-orders on :8082, servlet-warehouse on :8083 and batch-worker (no
# HTTP port) all instrumented and forwarding to it, then the load generator.
# Ctrl-C stops everything.
#
#   scripts/demo-shared.sh            build first, then run
#   scripts/demo-shared.sh --no-build run what is already built
#   RPS=8 scripts/demo-shared.sh      load generator rate (default 4)
set -euo pipefail
cd "$(dirname "$0")/.."

SENSE_PORT="${SENSE_PORT:-4000}"
RPS="${RPS:-4}"
LOGS="build/demo-logs"
mkdir -p "$LOGS"

if [[ "${1:-}" != "--no-build" ]]; then
    ./gradlew --quiet :spider-sense-agent:senseJar \
        :examples:silk-bookstore:installDist \
        :examples:spring-orders:bootJar \
        :examples:servlet-warehouse:installDist \
        :examples:batch-worker:installDist \
        :examples:load-gen:installDist
fi

SENSE_JAR="$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
BOOKSTORE="examples/silk-bookstore/build/install/silk-bookstore/bin/silk-bookstore"
ORDERS_JAR="$(ls examples/spring-orders/build/libs/spring-orders-*.jar | grep -v -- '-plain' | head -1)"
WAREHOUSE="examples/servlet-warehouse/build/install/servlet-warehouse/bin/servlet-warehouse"
WORKER="examples/batch-worker/build/install/batch-worker/bin/batch-worker"
LOADGEN="examples/load-gen/build/install/load-gen/bin/load-gen"

pids=()
stop() {
    echo
    echo "Stopping..."
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap stop EXIT INT TERM

wait_for() {   # wait_for <url> <name>
    for _ in $(seq 1 120); do
        if curl -sf -o /dev/null "$1"; then return 0; fi
        sleep 1
    done
    echo "$2 did not come up at $1; see $LOGS" >&2
    return 1
}

echo "Spider Sense (standalone) -> $LOGS/spider-sense.log"
java -jar "$SENSE_JAR" --port="$SENSE_PORT" >"$LOGS/spider-sense.log" 2>&1 &
pids+=($!)
wait_for "http://127.0.0.1:$SENSE_PORT/api/status" "Spider Sense"

AGENT_OPTS="-javaagent:$PWD/$SENSE_JAR -Dspidersense.collector=http://127.0.0.1:$SENSE_PORT"

echo "silk-bookstore (:8081) -> $LOGS/silk-bookstore.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=silk-bookstore" "$BOOKSTORE" >"$LOGS/silk-bookstore.log" 2>&1 &
pids+=($!)

echo "spring-orders (:8082) -> $LOGS/spring-orders.log"
java $AGENT_OPTS -Dotel.service.name=spring-orders -Dotel.instrumentation.micrometer.enabled=true -jar "$ORDERS_JAR" >"$LOGS/spring-orders.log" 2>&1 &
pids+=($!)

echo "servlet-warehouse (:8083) -> $LOGS/servlet-warehouse.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=servlet-warehouse" "$WAREHOUSE" >"$LOGS/servlet-warehouse.log" 2>&1 &
pids+=($!)

echo "batch-worker (no HTTP port) -> $LOGS/batch-worker.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=batch-worker" "$WORKER" >"$LOGS/batch-worker.log" 2>&1 &
pids+=($!)

wait_for "http://127.0.0.1:8081/api/health" "silk-bookstore"
wait_for "http://127.0.0.1:8082/api/health" "spring-orders"
wait_for "http://127.0.0.1:8083/api/health" "servlet-warehouse"

echo
echo "  Spider Sense UI   : http://127.0.0.1:$SENSE_PORT"
echo "  silk-bookstore    : http://127.0.0.1:8081"
echo "  spring-orders     : http://127.0.0.1:8082"
echo "  servlet-warehouse : http://127.0.0.1:8083"
echo "  batch-worker      : no HTTP port"
echo
echo "  Findings: java -jar $SENSE_JAR findings --since=start --url=http://127.0.0.1:$SENSE_PORT"
echo "  Check:    java -jar $SENSE_JAR check --since=start --url=http://127.0.0.1:$SENSE_PORT"
echo
echo "load-gen ($RPS rps) -> $LOGS/load-gen.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=load-gen" "$LOADGEN" --rps="$RPS" --wait=90 >"$LOGS/load-gen.log" 2>&1 &
pids+=($!)

echo "Ctrl-C to stop."
wait
