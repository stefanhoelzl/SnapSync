# shellcheck shell=sh
# What the two all-real journey runners share — `scripts/sim-contracts` (the iOS simulator) and
# `scripts/android-journeys` (the Android emulator). Sourced from the repository root, never run, and POSIX sh, because
# sim-contracts is:
#
#   . scripts/lib/journeys.sh
#
# The caller sets OUT (its evidence directory) and BACKEND (the local backend's API base) and defines `fail <message>`,
# which records the failure in its own words and exits non-zero.

# A timestamped marker per stage, kept with the evidence, so the job's wall-clock can be attributed.
stage() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT/stages"; }

# Wait for the local backend, started earlier in the background, to answer (its first start downloads its
# dependencies); fail with the tail of its log if it never does.
await_backend() {
    i=0
    until curl -s --max-time 5 -o /dev/null "$BACKEND/events/00000000-0000-4000-8000-000000000000"; do
        i=$((i + 1)); [ $i -lt 90 ] || { tail -40 "$OUT/backend.log"; fail "the local backend never answered on $BACKEND"; }; sleep 2
    done
    stage "backend: answering"
}

# Run the journeys against the app whose control channel answers on $1 and the local backend, logging to
# $OUT/journeys.log; returns JUnit's exit code. A bare JVM on the classpath written at compile time, NOT Gradle: a Gradle
# daemon and a test JVM starting next to the live simulator pushed the runner into swap, and the app missed its 5 s HTTP
# timeout on a request the backend had answered in 132 ms (run 36173548419) — the same holds beside the emulator.
run_journeys() {
    stage "journeys: starting"
    "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m -cp "$(cat test/integration/build/journeys-classpath.txt)" \
        -Dsnapsync.journey.appA="$1" -Dsnapsync.journey.backend="$BACKEND" \
        org.junit.runner.JUnitCore app.snapsync.journeys.Journeys > "$OUT/journeys.log" 2>&1
    journeys_status=$?
    stage "journeys: done"
    return $journeys_status
}

# JUnit's own failure section of $OUT/journeys.log: the test, and the assertion's message, without the stack frames.
print_journey_failures() {
    sed -n '/^There w/,/^FAILURES/p' "$OUT/journeys.log" | grep -vE '^[[:space:]]+at ' | head -30
}
