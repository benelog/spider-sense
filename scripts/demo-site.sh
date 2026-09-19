#!/usr/bin/env bash
# The published demo (docs/design.md, "The published demo"):
#
#   scripts/demo-site.sh record   [--no-build]            run the shared demo for MINUTES (5), mark
#                                                         before and after, and push the window's
#                                                         rows to the DoltHub database (DOLTHUB_TOKEN)
#   scripts/demo-site.sh assemble [--no-build] [<out dir>] pull the rows from DoltHub, load them into a
#                                                         fresh Spider Sense on DEMO_PORT (4090), capture
#                                                         every answer the UI asks for, and write the
#                                                         static page to <out dir> (build/demo-site)
#
# Both build the jar unless --no-build; neither touches ~/db/spider-sense beyond reading it.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    sed -n '2,12p' "$0" >&2
    exit 2
}

COMMAND="${1:-}"
shift || true
BUILD=1
OUT="build/demo-site"
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        --*) echo "unknown option: $arg" >&2; usage ;;
        *) OUT="$arg" ;;
    esac
done

DATA="build/demo-data"
LOGS="build/demo-logs"
mkdir -p "$DATA" "$LOGS"

sense_jar() {
    if [[ "$BUILD" == 1 ]]; then
        ./gradlew --quiet :spider-sense-agent:senseJar
    fi
    ls spider-sense-agent/build/libs/spider-sense-*.jar | grep -v -- '-launcher' | head -1
}

wait_for() {   # wait_for <url> <what> [seconds]
    for _ in $(seq 1 "${3:-120}"); do
        if curl -sf -o /dev/null "$1"; then return 0; fi
        sleep 1
    done
    echo "$2 did not come up at $1; see $LOGS" >&2
    return 1
}

now_ms() { date +%s%3N; }

record() {
    MINUTES="${MINUTES:-5}"
    SENSE_PORT="${SENSE_PORT:-4000}"
    export SENSE_PORT
    : "${DOLTHUB_TOKEN:?DOLTHUB_TOKEN is not set; the API token of a writer of the DoltHub database, kept in .envrc}"
    SENSE_JAR="$(sense_jar)"
    URL="http://127.0.0.1:$SENSE_PORT"

    LAUNCH_MS="$(now_ms)"
    if [[ "$BUILD" == 1 ]]; then scripts/demo-shared.sh & else scripts/demo-shared.sh --no-build & fi
    DEMO_PID=$!
    stop_demo() {
        if kill -0 "$DEMO_PID" 2>/dev/null; then
            kill "$DEMO_PID" 2>/dev/null || true
            wait "$DEMO_PID" 2>/dev/null || true
        fi
    }
    trap stop_demo EXIT INT TERM

    wait_for "$URL/api/status" "Spider Sense" 300
    wait_for http://127.0.0.1:8081/api/health silk-bookstore 300
    wait_for http://127.0.0.1:8082/api/health spring-orders 300
    wait_for http://127.0.0.1:8083/api/health servlet-warehouse 300

    total=$((MINUTES * 60))
    third=$((total / 3))
    echo "demo-site: recording for $MINUTES min; marks at $third s and $((third * 2)) s"
    sleep "$third"
    java -jar "$SENSE_JAR" mark before --url="$URL" >/dev/null
    sleep "$third"
    java -jar "$SENSE_JAR" mark after --url="$URL" >/dev/null
    sleep $((total - third * 2))
    sleep 2   # the writer's last flush

    TO_MS="$(now_ms)"
    FROM_MS=$((TO_MS - MINUTES * 60 * 1000))
    # Every row since the launch, so the start marks are there for `since=start`; the
    # page shows the last MINUTES of them.
    node scripts/demo-site.mjs export --jar="$SENSE_JAR" --db="${SPIDERSENSE_DB:-~/db/spider-sense/sense}" \
        --since="$LAUNCH_MS" --until="$TO_MS" --from="$FROM_MS" --to="$TO_MS"
    stop_demo
    trap - EXIT INT TERM

    node scripts/demo-site.mjs push
    echo
    echo "Recorded. The site rebuilds from it on the next push to main, or now with:"
    echo "  gh workflow run docs.yml"
}

assemble() {
    DEMO_PORT="${DEMO_PORT:-4090}"
    SENSE_JAR="$(sense_jar)"
    URL="http://127.0.0.1:$DEMO_PORT"
    DB="$PWD/$DATA/sense"

    node scripts/demo-site.mjs pull

    rm -f "$DB.mv.db" "$DB.trace.db"
    echo "Spider Sense (standalone, $DB) -> $LOGS/demo-site.log"
    # A century of retention: the rows are days or months old by the time the site is built.
    java -jar "$SENSE_JAR" --port="$DEMO_PORT" --db="$DB" --retention.hours=876000 --retention.spans=0 \
        >"$LOGS/demo-site.log" 2>&1 &
    SENSE_PID=$!
    stop_sense() {
        if kill -0 "$SENSE_PID" 2>/dev/null; then
            kill "$SENSE_PID" 2>/dev/null || true
            wait "$SENSE_PID" 2>/dev/null || true
        fi
    }
    trap stop_sense EXIT INT TERM
    wait_for "$URL/api/status" "Spider Sense"

    node scripts/demo-site.mjs load --jar="$SENSE_JAR" --db="$DB" --url="$URL"
    node scripts/demo-site.mjs capture --url="$URL"
    stop_sense
    trap - EXIT INT TERM

    node scripts/demo-site.mjs assemble "$OUT"
}

case "$COMMAND" in
    record) record ;;
    assemble) assemble ;;
    *) usage ;;
esac
