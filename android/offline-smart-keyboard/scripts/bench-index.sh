#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
kotlinc \
  app/src/main/java/dev/offline/smartkeyboard/core/FastCorrector.kt \
  app/src/main/java/dev/offline/smartkeyboard/core/T9Predictor.kt \
  app/src/main/java/dev/offline/smartkeyboard/core/ContextRanker.kt \
  app/src/main/java/dev/offline/smartkeyboard/core/BlockSearchCorrector.kt \
  jvm-tests/IndexBenchmark.kt \
  -include-runtime -d "$BUILD_DIR/bench.jar"
java -Xms256m -Xmx1g -jar "$BUILD_DIR/bench.jar" "${1:-10000}"
