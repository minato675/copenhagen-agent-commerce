#!/bin/sh
# CI artifact의 커밋, 파이프라인, 서비스, 내용이 모두 일치해야 사용합니다.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
action=${1:?record or verify is required}
module=${2:?module path is required}
case "$module" in
    apps/core-service|apps/mcp-server|apps/pay-service|mocks/ucp-merchant-mock|mocks/pg-mock) ;;
    *) echo "Unsupported JAR module: $module" >&2; exit 1 ;;
esac
: "${CI_COMMIT_SHA:?CI_COMMIT_SHA is required}"
: "${CI_PIPELINE_ID:?CI_PIPELINE_ID is required}"
jar="$root/backend/$module/build/libs/app.jar"
metadata="$jar.metadata"
test -s "$jar" || { echo "Missing JAR artifact: $module" >&2; exit 1; }
digest=$(sha256sum "$jar" | cut -d ' ' -f 1)
expected=$(printf '%s\n' "$CI_COMMIT_SHA" "$CI_PIPELINE_ID" "$module" "$digest")
case "$action" in
    record) printf '%s\n' "$expected" > "$metadata" ;;
    verify)
        if [ ! -f "$metadata" ] || [ "$(cat "$metadata")" != "$expected" ]; then
            echo "JAR artifact provenance mismatch: $module" >&2
            exit 1
        fi
        ;;
    *) echo "Unsupported JAR action: $action" >&2; exit 1 ;;
esac
printf 'JAR %s module=%s commit=%s pipeline=%s sha256=%s\n' \
    "$action" "$module" "$CI_COMMIT_SHA" "$CI_PIPELINE_ID" "$digest"
