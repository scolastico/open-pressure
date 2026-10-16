#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p protocol/build/plain-tests
rg --files protocol/src -g '*.java' > protocol/build/plain-tests/sources.txt
javac -encoding UTF-8 -d protocol/build/plain-tests @protocol/build/plain-tests/sources.txt
java -ea -cp protocol/build/plain-tests org.openpressure.protocol.ProtocolTest
