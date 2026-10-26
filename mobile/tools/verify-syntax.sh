#!/bin/sh
# Parser-only verification. No Android type resolution, Compose compilation, or Gradle sync.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
: "${KOTLIN_HOME:?Set KOTLIN_HOME to a standalone Kotlin compiler installation}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM
"$KOTLIN_HOME/bin/kotlinc" "$ROOT/tools/ParseKotlin.kt" -cp "$KOTLIN_HOME/lib/kotlin-compiler.jar" -d "$TMP/parser.jar"
java -cp "$TMP/parser.jar:$KOTLIN_HOME/lib/*" ParseKotlinKt "$ROOT"
