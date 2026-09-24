#!/usr/bin/env bash
# The agent demo (design.adoc#the-agent-demo): Claude Code or Codex CLI, run for
# real and headless on the prompt of the README's "Hand it to an agent", in English or
# Korean, with its event stream kept for a page that replays it.
#
#   scripts/agent-demo.sh record <claude|codex> <en|ko>   start the shared demo, let it take traffic
#                                                         for WARMUP_MINUTES (3), run the agent in this
#                                                         checkout, then stop the demo; the stream
#                                                         lands in build/agent-demo/
#   scripts/agent-demo.sh push                            turn the streams into rows and push them to
#                                                         DoltHub (DOLTHUB_TOKEN)
#
# The jar and the examples must be built (the prompt says --no-build) and the tree clean;
# nothing is edited, since the prompt asks for proposals before any change and Claude Code
# is given no edit tool.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    sed -n '2,16p' "$0" >&2
    exit 2
}

OUT="build/agent-demo"
mkdir -p "$OUT"

# What a user types once the applications have been running for a while: the skill by
# name, then the question. Claude Code calls a skill `/name`, Codex `$name`.
QUESTION_EN='The example apps have been running under Spider Sense with some traffic for a few minutes.
What are the three biggest problems, and which lines under examples/ cause them?
Do not change the code yet; propose how to fix them first.'
QUESTION_KO='예제 앱들이 Spider Sense를 붙인 채 몇 분째 트래픽을 받고 있어.
가장 큰 문제 세 가지와, 그 원인이 되는 examples/ 아래 코드 줄을 알려 줘.
코드는 고치지 말고 개선 방안을 먼저 제안해 줘.'

# The minutes of traffic before the session starts (the load generator's own first 90
# seconds included).
WARMUP_MINUTES="${WARMUP_MINUTES:-3}"
CODEX_EFFORT="${CODEX_EFFORT:-medium}"

FOREGROUND='This session is non-interactive: it ends when your turn ends, and no background notification will arrive. Wait in the foreground (a Bash call may run for up to ten minutes) and finish the whole task in this turn.'

# Every line of the stream with the time it arrived, since neither stream carries one.
stamp() {
    node -e 'require("readline").createInterface({ input: process.stdin })
        .on("line", (l) => process.stdout.write(Date.now() + "\t" + l + "\n"))'
}

# The demo and whatever the agent may have started beside it.
stop_demo() {
    pkill -f 'scripts/demo-shared.sh' 2>/dev/null || true
    pkill -f 'spider-sense-agent/build/libs/spider-sense-' 2>/dev/null || true
    sleep 3
    pkill -9 -f 'spider-sense-agent/build/libs/spider-sense-' 2>/dev/null || true
}

# The shared demo, up and under load for WARMUP_MINUTES, before the agent is started.
start_demo() {
    scripts/demo-shared.sh --no-build >"$OUT/$1.demo.log" 2>&1 &
    for _ in $(seq 1 300); do
        grep -q 'Ctrl-C to stop' "$OUT/$1.demo.log" && break
        sleep 1
    done
    grep -q 'Ctrl-C to stop' "$OUT/$1.demo.log" || { echo "the demo did not come up; see $OUT/$1.demo.log" >&2; exit 1; }
    echo "agent-demo: the demo is up; $WARMUP_MINUTES min of traffic before the session"
    sleep $((WARMUP_MINUTES * 60))
}

record() {
    local agent="$1" lang="$2" prompt file question skill
    case "$lang" in
        en) question="$QUESTION_EN" ;;
        ko) question="$QUESTION_KO" ;;
        *) usage ;;
    esac
    case "$agent" in
        claude) skill='/spider-sense' ;;
        codex) skill='$spider-sense' ;;
        *) usage ;;
    esac
    prompt="$skill $question"
    ls spider-sense-agent/build/libs/spider-sense-*.jar >/dev/null 2>&1 \
        || { echo "build the jar and the examples first (scripts/demo-shared.sh builds them)" >&2; exit 1; }
    # What the agent reads of the tree is the commit, not work in progress.
    [[ -z "$(git status --porcelain)" ]] || { echo "commit or stash first: the agent would see the changes" >&2; exit 1; }
    stop_demo
    # A database of the session's own, so its findings cover this run and nothing that
    # earlier runs left in ~/db/spider-sense/sense; every JVM of the demo and the CLI
    # read it from the properties file SPIDERSENSE_CONFIG names.
    local db
    db="$PWD/$OUT/db/$agent-$lang-$(date +%s)/sense"
    mkdir -p "$(dirname "$db")"
    printf 'spidersense.db=%s\n' "$db" >"$OUT/$agent-$lang.properties"
    export SPIDERSENSE_CONFIG="$PWD/$OUT/$agent-$lang.properties"
    start_demo "$agent-$lang"
    file="$OUT/$agent-$lang.jsonl"
    printf '%s\n' "$prompt" >"$OUT/$agent-$lang.prompt"
    echo "agent-demo: $agent ($lang) -> $file"
    date +%s%3N >"$OUT/$agent-$lang.started"
    case "$agent" in
        claude)
            # A nested session must not think it runs inside another one; the user's own
            # settings stay out so the answer is what the project gives any user, except the
            # language a Korean user would have set. `-p` ends with the turn, so a wait left
            # in the background would end the session.
            # The account's claude.ai connectors and the user's auto memory stay out too:
            # they are not the project's.
            env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT ENABLE_CLAUDEAI_MCP_SERVERS=false \
                CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 claude -p "$prompt" \
                --strict-mcp-config \
                --output-format stream-json --verbose \
                --setting-sources project,local \
                --append-system-prompt "$FOREGROUND" \
                --settings "$([[ "$lang" == ko ]] && echo '{"language":"korean"}' || echo '{}')" \
                --allowedTools 'Skill,Bash,Read,Grep,Glob,BashOutput,KillShell,Monitor,TaskOutput,TaskStop' \
                --disallowedTools 'Edit,Write,NotebookEdit,MultiEdit' \
                </dev/null 2>"$OUT/$agent-$lang.err" | stamp >"$file" || true
            ;;
        codex)
            codex --version | grep -o '[0-9][0-9.]*' >"$OUT/$agent-$lang.version"
            echo "$(sed -n 's/^model *= *"\(.*\)"/\1/p' "$HOME/.codex/config.toml" | head -1) $CODEX_EFFORT" >"$OUT/$agent-$lang.model"
            codex exec --json --sandbox workspace-write \
                -c sandbox_workspace_write.network_access=true \
                -c model_reasoning_effort="\"$CODEX_EFFORT\"" \
                "$prompt" </dev/null 2>"$OUT/$agent-$lang.err" | stamp >"$file" || true
            ;;
        *) usage ;;
    esac
    stop_demo
    echo "agent-demo: $(wc -l <"$file") events in $file"
}

case "${1:-}" in
    record) [[ $# == 3 ]] || usage; record "$2" "$3" ;;
    push) node scripts/demo-site.mjs agent-push --dir="$OUT" ;;
    *) usage ;;
esac
