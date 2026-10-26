#!/bin/sh
# Android/Gradle 없이 실제 Rules.kt만 컴파일하고 실행한다.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM
kotlinc "$ROOT/app/src/main/kotlin/com/copenhagen/wallet/data/Rules.kt" "$ROOT/tools/RulesCheck.kt" -include-runtime -d "$TMP/rules.jar"
java -jar "$TMP/rules.jar"
