#!/usr/bin/env bash
# The default demo, Glowroot-style: no separate process. Each application runs
# with -javaagent:spider-sense.jar and hosts its own Spider Sense UI on a port
# of its own, next to the application's port.
#
#   silk-bookstore    :8081   Spider Sense UI inside it  :4000
#   spring-orders     :8082   Spider Sense UI inside it  :4001
#   servlet-warehouse :8083   Spider Sense UI inside it  :4002
#   batch-worker      no HTTP Spider Sense UI inside it  :4003
#
# The load generator drives the three web applications; batch-worker runs its own
# jobs and takes no traffic. Ctrl-C stops everything.
#
#   scripts/demo.sh            build first, then run
#   scripts/demo.sh --no-build run what is already built
#   RPS=8 scripts/demo.sh      load generator rate (default 4)
#
# scripts/demo-shared.sh is the other layout: one standalone Spider Sense that
# every application forwards to, so a trace crossing two of them shows in one UI.
set -euo pipefail
cd "$(dirname "$0")/.."

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

SENSE_JAR="$PWD/$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
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

echo "silk-bookstore (:8081, Spider Sense :4000) -> $LOGS/silk-bookstore.log"
JAVA_OPTS="-javaagent:$SENSE_JAR -Dspidersense.port=4000 -Dotel.service.name=silk-bookstore" \
    "$BOOKSTORE" >"$LOGS/silk-bookstore.log" 2>&1 &
pids+=($!)

echo "spring-orders (:8082, Spider Sense :4001) -> $LOGS/spring-orders.log"
java -javaagent:"$SENSE_JAR" -Dspidersense.port=4001 -Dotel.service.name=spring-orders -Dotel.instrumentation.micrometer.enabled=true \
    -jar "$ORDERS_JAR" >"$LOGS/spring-orders.log" 2>&1 &
pids+=($!)

echo "servlet-warehouse (:8083, Spider Sense :4002) -> $LOGS/servlet-warehouse.log"
JAVA_OPTS="-javaagent:$SENSE_JAR -Dspidersense.port=4002 -Dotel.service.name=servlet-warehouse" \
    "$WAREHOUSE" >"$LOGS/servlet-warehouse.log" 2>&1 &
pids+=($!)

echo "batch-worker (no HTTP port, Spider Sense :4003) -> $LOGS/batch-worker.log"
JAVA_OPTS="-javaagent:$SENSE_JAR -Dspidersense.port=4003 -Dotel.service.name=batch-worker" \
    "$WORKER" >"$LOGS/batch-worker.log" 2>&1 &
pids+=($!)

wait_for "http://127.0.0.1:8081/api/health" "silk-bookstore"
wait_for "http://127.0.0.1:8082/api/health" "spring-orders"
wait_for "http://127.0.0.1:8083/api/health" "servlet-warehouse"
wait_for "http://127.0.0.1:4003/api/status" "batch-worker"

echo
echo "  silk-bookstore    : http://127.0.0.1:8081   Spider Sense: http://127.0.0.1:4000"
echo "  spring-orders     : http://127.0.0.1:8082   Spider Sense: http://127.0.0.1:4001"
echo "  servlet-warehouse : http://127.0.0.1:8083   Spider Sense: http://127.0.0.1:4002"
echo "  batch-worker      : no HTTP port            Spider Sense: http://127.0.0.1:4003"
echo
echo "  Findings: java -jar $SENSE_JAR findings --since=start"
echo "  Check:    java -jar $SENSE_JAR check --since=start"
echo
echo "load-gen ($RPS rps) -> $LOGS/load-gen.log"
"$LOADGEN" --rps="$RPS" --wait=90 >"$LOGS/load-gen.log" 2>&1 &
pids+=($!)

echo "Ctrl-C to stop."
wait
