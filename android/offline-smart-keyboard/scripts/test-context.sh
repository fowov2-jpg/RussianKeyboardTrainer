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
  jvm-tests/ContextTest.kt \
  -include-runtime -d "$BUILD_DIR/context.jar"
java -jar "$BUILD_DIR/context.jar" app/src/main/assets/ru_words.txt app/src/main/assets/ru_bigrams.txt
