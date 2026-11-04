#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
kotlinc app/src/main/java/dev/offline/smartkeyboard/core/FastCorrector.kt \
  app/src/main/java/dev/offline/smartkeyboard/core/T9Predictor.kt \
  jvm-tests/SmokeTest.kt jvm-tests/T9Test.kt \
  -include-runtime -d "$BUILD_DIR/smoke.jar"
java -jar "$BUILD_DIR/smoke.jar" app/src/main/assets/ru_words.txt
