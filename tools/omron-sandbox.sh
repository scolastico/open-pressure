#!/usr/bin/env bash
# Replays shared device fixtures side by side: Omron Connect's own code (sandbox) and OpenPressure.
# Setup and fixture syntax: docs/omron-sandbox.md
set -euo pipefail
cd "$(dirname "$0")/.."

out=omron-sandbox/build/omron-sandbox
mkdir -p omron-sandbox/build
usage() {
  echo "usage: tools/omron-sandbox.sh [all|<fixture>]   replay fixtures with both clients"
  echo "       tools/omron-sandbox.sh resolve            show how Omron's entry points were found"
  echo "       tools/omron-sandbox.sh diff <old> <new>   compare transcripts of two Omron versions"
}
case "${1:-all}" in
  -h|--help) usage; exit 0 ;;
  resolve)
    rm -f "$out/resolve.txt"
    ./gradlew -q :omron-sandbox:testDebugUnitTest --tests '*OmronAnchorsTest' >/dev/null
    cat "$out/resolve.txt" 2>/dev/null || echo "Omron: skipped (Omron APK not found, see docs/omron-sandbox.md)"
    exit 0 ;;
  diff)
    [ $# -eq 3 ] || { usage; exit 2; }
    ls "$out/transcripts/$2" "$out/transcripts/$3" >/dev/null
    # .log files hold virtual-time steps and Omron's raw log; they differ between any two runs.
    diff -ru -x '*.log' "$out/transcripts/$2" "$out/transcripts/$3" && echo "No transcript differences."
    exit 0 ;;
esac

fixture=${1:-all}
index=protocol/src/testFixtures/resources/org/openpressure/protocol/fixtures/index
if [ "$fixture" = all ]; then names=$(grep -v '^\s*\(#\|$\)' "$index"); else
  grep -qx "$fixture" "$index" || { echo "Unknown fixture $fixture (see $index)"; exit 2; }
  names=$fixture
fi

rm -rf "$out/results" app/build/fixture-replay
# Our app replay fails the build when OpenPressure diverges; keep going so both sides are reported.
./gradlew -q --continue -Dfixture="$fixture" \
  :omron-sandbox:testDebugUnitTest --tests '*OmronFixtureTest' --rerun \
  :app:testDebugUnitTest --tests '*FixtureReplayTest' --rerun >"$out-gradle.log" 2>&1 || true

mkdir -p protocol/build/plain-tests
rg --files protocol/src -g '*.java' > protocol/build/plain-tests/sources.txt
javac -encoding UTF-8 -d protocol/build/plain-tests @protocol/build/plain-tests/sources.txt

for name in $names; do
  echo "== $name"
  if [ -f "$out/results/$name.txt" ]; then cat "$out/results/$name.txt"
  else echo "omron: skipped (Omron APK not found, see docs/omron-sandbox.md)"; fi
  if [ -f "app/build/fixture-replay/$name.txt" ]; then head -1 "app/build/fixture-replay/$name.txt"
  else echo "ours (app): not run (see ./gradlew :app:testDebugUnitTest --tests '*FixtureReplayTest')"; fi
  java -cp protocol/build/plain-tests:protocol/src/testFixtures/resources org.openpressure.protocol.fixture.ProtocolReplay "$name"
  echo
done
