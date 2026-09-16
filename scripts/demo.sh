#!/usr/bin/env bash
# The default demo, Glowroot-style: no separate process. Each application runs
# with -javaagent:spider-sense.jar and hosts its own Spider Sense UI on a port
# of its own, next to the application's port.
#
#   silk-bookstore  :8081   Spider Sense UI inside it  :4000
#   spring-orders   :8082   Spider Sense UI inside it  :4001
#
# The load generator drives both. Ctrl-C stops everything.
#
#   scripts/demo.sh            build first, then run
#   scripts/demo.sh --no-build run what is already built
#   RPS=8 scripts/demo.sh      load generator rate (default 4)
#
# scripts/demo-shared.sh is the other layout: one standalone Spider Sense that
# both applications forward to, so a trace crossing both shows in one UI.
set -euo pipefail
cd "$(dirname "$0")/.."

RPS="${RPS:-4}"
LOGS="build/demo-logs"
mkdir -p "$LOGS"

if [[ "${1:-}" != "--no-build" ]]; then
    ./gradlew --quiet :spider-sense-agent:senseJar \
        :examples:silk-bookstore:installDist \
        :examples:spring-orders:bootJar \
        :examples:load-gen:installDist
fi

SENSE_JAR="$PWD/$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
BOOKSTORE="examples/silk-bookstore/build/install/silk-bookstore/bin/silk-bookstore"
ORDERS_JAR="$(ls examples/spring-orders/build/libs/spring-orders-*.jar | grep -v -- '-plain' | head -1)"
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
java -javaagent:"$SENSE_JAR" -Dspidersense.port=4001 -Dotel.service.name=spring-orders \
    -jar "$ORDERS_JAR" >"$LOGS/spring-orders.log" 2>&1 &
pids+=($!)

wait_for "http://127.0.0.1:8081/api/health" "silk-bookstore"
wait_for "http://127.0.0.1:8082/api/health" "spring-orders"

echo
echo "  silk-bookstore  : http://127.0.0.1:8081   Spider Sense: http://127.0.0.1:4000"
echo "  spring-orders   : http://127.0.0.1:8082   Spider Sense: http://127.0.0.1:4001"
echo
echo "load-gen ($RPS rps) -> $LOGS/load-gen.log"
"$LOADGEN" --rps="$RPS" >"$LOGS/load-gen.log" 2>&1 &
pids+=($!)

echo "Ctrl-C to stop."
wait
