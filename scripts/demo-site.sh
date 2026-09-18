#!/usr/bin/env bash
# Records the demo as a static site: runs scripts/demo-shared.sh, lets the load
# generator work for a while, marks two moments so the compare page has
# something to compare, captures every answer the UI asks for into demo/data/,
# stops the demo, and assembles build/demo-site/ from the UI and that data.
#
#   scripts/demo-site.sh [--no-build] [--no-capture]
#
# MINUTES (default 5) is how long the demo runs before the capture; the
# recording covers that window. --no-capture only assembles the site from the
# demo/data/ already there, which is what `npm run docs` does for the manual's
# /demo page.
set -euo pipefail
cd "$(dirname "$0")/.."

MINUTES="${MINUTES:-5}"
BUILD_FLAG=""
CAPTURE=1
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD_FLAG="--no-build" ;;
        --no-capture) CAPTURE=0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

if [[ "$CAPTURE" == 1 ]]; then
    if [[ -z "$BUILD_FLAG" ]]; then
        ./gradlew --quiet :spider-sense-agent:senseJar
    fi
    SENSE_JAR="$(ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1)"
    SENSE_PORT="${SENSE_PORT:-4000}"
    export SENSE_PORT

    scripts/demo-shared.sh $BUILD_FLAG &
    DEMO_PID=$!
    stop_demo() {
        if kill -0 "$DEMO_PID" 2>/dev/null; then
            kill "$DEMO_PID" 2>/dev/null || true
            wait "$DEMO_PID" 2>/dev/null || true
        fi
    }
    trap stop_demo EXIT INT TERM

    for _ in $(seq 1 300); do
        if curl -sf -o /dev/null "http://127.0.0.1:$SENSE_PORT/api/status" \
            && curl -sf -o /dev/null http://127.0.0.1:8081/api/health \
            && curl -sf -o /dev/null http://127.0.0.1:8082/api/health \
            && curl -sf -o /dev/null http://127.0.0.1:8083/api/health; then
            break
        fi
        sleep 1
    done

    total=$((MINUTES * 60))
    third=$((total / 3))
    echo "demo-site: recording for $MINUTES min; marks at $third s and $((third * 2)) s"
    sleep "$third"
    java -jar "$SENSE_JAR" mark before --url="http://127.0.0.1:$SENSE_PORT" >/dev/null
    sleep "$third"
    java -jar "$SENSE_JAR" mark after --url="http://127.0.0.1:$SENSE_PORT" >/dev/null
    sleep $((total - third * 2))

    node scripts/demo-site.mjs capture --url="http://127.0.0.1:$SENSE_PORT" --out=demo/data --minutes="$MINUTES"
    stop_demo
    trap - EXIT INT TERM
fi

node scripts/demo-site.mjs assemble build/demo-site --data=demo/data
