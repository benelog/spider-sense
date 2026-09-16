#!/usr/bin/env bash
# The shared layout: one standalone Spider Sense on :4000, silk-bookstore on :8081
# and spring-orders on :8082 both instrumented and forwarding to it, then the
# load generator. Ctrl-C stops everything.
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
        :examples:load-gen:installDist
fi

SENSE_JAR="$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
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

echo "Spider Sense (standalone) -> $LOGS/spider-sense.log"
java -jar "$SENSE_JAR" --port="$SENSE_PORT" >"$LOGS/spider-sense.log" 2>&1 &
pids+=($!)
wait_for "http://127.0.0.1:$SENSE_PORT/api/status" "Spider Sense"

AGENT_OPTS="-javaagent:$PWD/$SENSE_JAR -Dspidersense.collector=http://127.0.0.1:$SENSE_PORT"

echo "silk-bookstore (:8081) -> $LOGS/silk-bookstore.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=silk-bookstore" "$BOOKSTORE" >"$LOGS/silk-bookstore.log" 2>&1 &
pids+=($!)

echo "spring-orders (:8082) -> $LOGS/spring-orders.log"
java $AGENT_OPTS -Dotel.service.name=spring-orders -jar "$ORDERS_JAR" >"$LOGS/spring-orders.log" 2>&1 &
pids+=($!)

wait_for "http://127.0.0.1:8081/api/health" "silk-bookstore"
wait_for "http://127.0.0.1:8082/api/health" "spring-orders"

echo
echo "  Spider Sense UI : http://127.0.0.1:$SENSE_PORT"
echo "  silk-bookstore  : http://127.0.0.1:8081"
echo "  spring-orders   : http://127.0.0.1:8082"
echo
echo "load-gen ($RPS rps) -> $LOGS/load-gen.log"
JAVA_OPTS="$AGENT_OPTS -Dotel.service.name=load-gen" "$LOADGEN" --rps="$RPS" >"$LOGS/load-gen.log" 2>&1 &
pids+=($!)

echo "Ctrl-C to stop."
wait
