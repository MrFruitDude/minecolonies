#!/usr/bin/env bash
# Request-system stress benchmark runner. See BENCH.md.
#
#   tools/rs_bench.sh [quick|full] [options]
#
# Options:
#   --reps K        measured repeats per test (default 5 full / 2 quick); one extra warm-up repeat runs first
#   --only a,b      only tests whose name contains one of the words (e.g. micro_1000,fresh)
#   --flags on|off  RS reservation + smart-retry switches (default on = the shipped default)
#   --seed N        seed of every random choice (default 117)
#   --jfr FILE      record the JVMs of the run with Java Flight Recorder (profile settings); put %p in FILE (one file per JVM, the big one is the game)
#   --out DIR       result directory (default bench-results/)
#   --keep-raw      keep the per-test JSON files next to the merged result
#
# Writes <out>/<git-sha>-<timestamp>.json (merged, with score) and prints the summary.
# The GameTest server run is wrapped in the shared lock so it never overlaps another worker's GameTest run.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"

mode="${1:-quick}"
case "$mode" in quick|full) shift ;; *) mode=quick ;; esac
reps=""; only=""; flags=on; seed=117; jfr=""; out="$here/bench-results"; keep=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --reps) reps="$2"; shift 2 ;;
    --only) only="$2"; shift 2 ;;
    --flags) flags="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --jfr) jfr="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --keep-raw) keep=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[[ -z "$reps" ]] && { [[ "$mode" == full ]] && reps=5 || reps=2; }

sha="$(git rev-parse --short=10 HEAD)"
dirty=""; git diff --quiet -- src || dirty="-dirty"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
raw="$here/bench-tmp/$sha$dirty-$stamp"
mkdir -p "$raw" "$out"

export RS_BENCH_ONLY=1 RS_BENCH_MODE="$mode" RS_BENCH_REPS="$reps" RS_BENCH_FLAGS="$flags" RS_BENCH_SEED="$seed" RS_BENCH_OUT="$raw"
[[ -n "$only" ]] && export RS_BENCH_ONLY_TESTS="$only"

gradle_args=(--no-daemon --offline -Dmaven.repo.local=/mnt/data/mc-mod-ports/rs-maven -x neoFormJoined26.3-1DownloadAssets runGameTestServer)
if [[ -n "$jfr" ]]; then
  # The task takes no --jvm-args; JAVA_TOOL_OPTIONS reaches every JVM of the run (gradle's too, whose small recordings are
  # harmless; the game JVM's is the big one). %p in the file name keeps them apart.
  export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=${TMPDIR:-/tmp} -Djna.tmpdir=${TMPDIR:-/tmp} -XX:StartFlightRecording=filename=$jfr,settings=profile,dumponexit=true,jdk.ExecutionSample#period=1ms -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints"
fi

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Djava.io.tmpdir=${TMPDIR:-/tmp} -Djna.tmpdir=${TMPDIR:-/tmp}}"   # sandboxes whose /tmp is read-only

log="$raw/gametest.log"
echo "rs_bench: mode=$mode reps=$reps flags=$flags seed=$seed sha=$sha$dirty raw=$raw"
set +e
# gametest lock outside, the one global Gradle lock inside (every sandboxed Gradle build, fork or CC, shares ~/.gradle/caches/journal-1 and
# cannot ask another sandbox's daemon to release it), after waiting for the Gradle caches to be free; --no-daemon so no daemon keeps them locked.
cache_wait=/home/mrfruitdude/Projects/MineColonies/anno117-re/tools/gradle-cache-wait.sh
flock /mnt/data/anno117-extract/.cc-gametest.lock bash -c '
  [ -x "$1" ] && "$1" 9.2.0 5400
  shift
  exec flock /mnt/data/anno117-extract/.gradle-global.lock timeout 3300 ./gradlew "$@"' _ "$cache_wait" "${gradle_args[@]}" > "$log" 2>&1
status=$?
# A Gradle cache lock held by a build in another sandbox ("Timeout waiting to lock ... Owner PID") is not our failure: wait (no tight loop) and run again.
for attempt in 1 2 3 4 5 6; do
  grep -q "Timeout waiting to lock" "$log" || break
  echo "rs_bench: Gradle cache busy (another build), waiting 240 s before attempt $((attempt + 1))" >&2
  sleep 240
  flock /mnt/data/anno117-extract/.cc-gametest.lock bash -c '
    [ -x "$1" ] && "$1" 9.2.0 5400
    shift
    exec flock /mnt/data/anno117-extract/.gradle-global.lock timeout 3300 ./gradlew "$@"' _ "$cache_wait" "${gradle_args[@]}" > "$log" 2>&1
  status=$?
done
set -e
echo "rs_bench: gametest server exit status $status (log: $log)"

python3 -I "$here/tools/rs_bench_report.py" merge "$raw" --sha "$sha$dirty" --mode "$mode" --reps "$reps" --flags "$flags" --seed "$seed" --out "$out/$sha$dirty-$stamp.json" --status "$status"
rc=$?
[[ $keep -eq 0 ]] && rm -f "$raw"/*.json "$raw"/gametest.log.gz && gzip -f "$log" || true
exit $rc
