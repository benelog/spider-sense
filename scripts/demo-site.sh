#!/usr/bin/env bash
# The published demo (docs/design.md, "The published demo"). Its rows and the answers
# the UI asks for live in a DoltHub database; the page reads them from there.
#
#   scripts/demo-site.sh record    [--no-build]   run the shared demo for MINUTES (5), mark before
#                                                 and after, export the rows, load them into a fresh
#                                                 Spider Sense, capture every answer the UI asks for,
#                                                 and push rows and answers to DoltHub (DOLTHUB_TOKEN)
#   scripts/demo-site.sh recapture [--no-build]   the same from the rows already on DoltHub: pull,
#                                                 load, capture, push the answers; what to run after
#                                                 a change to the server's queries
#
# The fresh Spider Sense listens on DEMO_PORT (4090) and keeps its file under build/demo-data/.
# Both build the jar unless --no-build. The static page itself is `node scripts/demo-site.mjs
# assemble`, which needs neither Java nor the token.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    sed -n '2,15p' "$0" >&2
    exit 2
}

COMMAND="${1:-}"
shift || true
BUILD=1
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        *) echo "unknown argument: $arg" >&2; usage ;;
    esac
done

DATA="build/demo-data"
LOGS="build/demo-logs"
DEMO_PORT="${DEMO_PORT:-4090}"
mkdir -p "$DATA" "$LOGS"
: "${DOLTHUB_TOKEN:?DOLTHUB_TOKEN is not set; the API token of a writer of the DoltHub database, kept in .envrc}"

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

# The shared demo for MINUTES, then every row since its launch as CSV files.
export_demo() {
    MINUTES="${MINUTES:-5}"
    SENSE_PORT="${SENSE_PORT:-4000}"
    export SENSE_PORT
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
    TO_MS="$(now_ms)"
    FROM_MS=$((TO_MS - MINUTES * 60 * 1000))
    # The traces that started inside the window end, and the agents' batch exporters
    # (five seconds apart) deliver them; then the demo stops, so the file is still
    # while it is read and no trace is half exported.
    sleep 10
    stop_demo
    trap - EXIT INT TERM

    # Every row since the launch, so the start marks are there for `since=start`; the
    # page shows the last MINUTES of them.
    node scripts/demo-site.mjs export --jar="$SENSE_JAR" --db="${SPIDERSENSE_DB:-~/db/spider-sense/sense}" \
        --since="$LAUNCH_MS" --until="$TO_MS" --from="$FROM_MS" --to="$TO_MS"
}

# The CSV files into a fresh Spider Sense, and every answer the UI asks for out of it.
capture_answers() {
    URL="http://127.0.0.1:$DEMO_PORT"
    DB="$PWD/$DATA/sense"
    rm -f "$DB.mv.db" "$DB.trace.db"
    echo "Spider Sense (standalone, $DB) -> $LOGS/demo-site.log"
    # A century of retention: the rows may be days old when this runs.
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
}

done_line() {
    echo
    echo "Pushed. The page at spider-sense.benelog.net/demo reads the database as it is now;"
    echo "a UI change still needs the Docs workflow (gh workflow run docs.yml)."
}

case "$COMMAND" in
    record)
        SENSE_JAR="$(sense_jar)"
        export_demo
        capture_answers
        node scripts/demo-site.mjs push
        done_line
        ;;
    recapture)
        SENSE_JAR="$(sense_jar)"
        node scripts/demo-site.mjs pull
        capture_answers
        node scripts/demo-site.mjs push --only=answer
        done_line
        ;;
    *) usage ;;
esac
