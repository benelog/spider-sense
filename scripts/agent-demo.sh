#!/usr/bin/env bash
# The agent demo (docs/design.md, "The agent demo"): Claude Code or Codex CLI, run for
# real and headless on the prompt of the README's "Hand it to an agent", in English or
# Korean, with its event stream kept for a page that replays it.
#
#   scripts/agent-demo.sh record <claude|codex> <en|ko>   run the agent in this checkout (it starts
#                                                         and reads the demo itself), then stop the
#                                                         demo; the stream lands in build/agent-demo/
#   scripts/agent-demo.sh push                            turn the streams into rows and push them to
#                                                         DoltHub (DOLTHUB_TOKEN)
#
# The jar and the examples must be built (the prompt says --no-build) and the tree clean;
# nothing is edited, since the prompt says not to fix anything and Claude Code is given no
# edit tool.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    sed -n '2,15p' "$0" >&2
    exit 2
}

OUT="build/agent-demo"
mkdir -p "$OUT"

PROMPT_EN='Read skills/spider-sense/SKILL.md, then start the demo with `scripts/demo-shared.sh --no-build`
in the background and wait until it prints the URLs. Run `mark demo`, let the load generator
run for two minutes, then run `findings --since=demo`. For each of the top three findings open
one of its traces and tell me which line under examples/ causes it. Do not fix anything.'

PROMPT_KO='skills/spider-sense/SKILL.md를 읽고, `scripts/demo-shared.sh --no-build`로 데모를
백그라운드에서 시작한 뒤 URL이 출력될 때까지 기다려 줘. `mark demo`를 실행하고, 부하 생성기가
2분 동안 돌게 둔 다음 `findings --since=demo`를 실행해 줘. 상위 세 개의 finding마다 trace를
하나씩 열어서, examples/ 아래 어느 줄이 원인인지 알려 줘. 아무것도 고치지는 마.'

FOREGROUND='This session is non-interactive: it ends when your turn ends, and no background notification will arrive. Wait in the foreground (a Bash call may run for up to ten minutes) and finish the whole task in this turn.'

# Every line of the stream with the time it arrived, since neither stream carries one.
stamp() {
    node -e 'require("readline").createInterface({ input: process.stdin })
        .on("line", (l) => process.stdout.write(Date.now() + "\t" + l + "\n"))'
}

# Whatever the agent started: the demo script, Spider Sense and the applications under it.
stop_demo() {
    pkill -f 'scripts/demo-shared.sh' 2>/dev/null || true
    pkill -f 'spider-sense-agent/build/libs/spider-sense-' 2>/dev/null || true
    sleep 3
    pkill -9 -f 'spider-sense-agent/build/libs/spider-sense-' 2>/dev/null || true
}

record() {
    local agent="$1" lang="$2" prompt file
    case "$lang" in
        en) prompt="$PROMPT_EN" ;;
        ko) prompt="$PROMPT_KO" ;;
        *) usage ;;
    esac
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
                --allowedTools 'Bash,Read,Grep,Glob,BashOutput,KillShell,Monitor,TaskOutput,TaskStop' \
                --disallowedTools 'Edit,Write,NotebookEdit,MultiEdit' \
                </dev/null 2>"$OUT/$agent-$lang.err" | stamp >"$file" || true
            ;;
        codex)
            codex exec --json --sandbox workspace-write \
                -c sandbox_workspace_write.network_access=true \
                --add-dir "$HOME/db/spider-sense" \
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
