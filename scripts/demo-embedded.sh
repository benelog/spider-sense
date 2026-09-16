#!/usr/bin/env bash
# The Glowroot-style mode: one application with the Spider Sense UI embedded in
# its own JVM. silk-bookstore runs on :8081, its Spider Sense on :4000, and the
# load generator drives only the bookstore.
#
#   scripts/demo-embedded.sh            build first, then run
#   scripts/demo-embedded.sh --no-build
set -euo pipefail
cd "$(dirname "$0")/.."

SENSE_PORT="${SENSE_PORT:-4000}"
RPS="${RPS:-4}"
LOGS="build/demo-logs"
mkdir -p "$LOGS"

if [[ "${1:-}" != "--no-build" ]]; then
    ./gradlew --quiet :spider-sense-agent:senseJar \
        :examples:silk-bookstore:installDist \
        :examples:load-gen:installDist
fi

SENSE_JAR="$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
BOOKSTORE="examples/silk-bookstore/build/install/silk-bookstore/bin/silk-bookstore"
LOADGEN="examples/load-gen/build/install/load-gen/bin/load-gen"

pids=()
stop() {
    echo
    echo "Stopping..."
    for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    wait 2>/dev/null || true
}
trap stop EXIT INT TERM

echo "silk-bookstore with embedded Spider Sense -> $LOGS/silk-bookstore-embedded.log"
JAVA_OPTS="-javaagent:$PWD/$SENSE_JAR -Dspidersense.port=$SENSE_PORT -Dotel.service.name=silk-bookstore" \
    "$BOOKSTORE" >"$LOGS/silk-bookstore-embedded.log" 2>&1 &
pids+=($!)

for _ in $(seq 1 120); do
    curl -sf -o /dev/null "http://127.0.0.1:8081/api/health" && break
    sleep 1
done

echo
echo "  Spider Sense UI : http://127.0.0.1:$SENSE_PORT   (inside the bookstore JVM)"
echo "  silk-bookstore  : http://127.0.0.1:8081"
echo
"$LOADGEN" --rps="$RPS" --orders=http://127.0.0.1:1 >"$LOGS/load-gen.log" 2>&1 &
pids+=($!)
echo "Ctrl-C to stop."
wait
