#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
module=${1:?module path is required}
case "$module" in
    apps/core-service|apps/mcp-server|apps/pay-service|mocks/ucp-merchant-mock|mocks/pg-mock) ;;
    *) echo "Unsupported JAR module: $module" >&2; exit 1 ;;
esac
jar="$root/backend/$module/build/libs/app.jar"
# 테스트가 선택된 서비스는 artifact가 없더라도 다시 빌드하지 않습니다.
if [ "${REQUIRE_TEST_ARTIFACT:-false}" = true ] || [ -e "$jar" ] || [ -e "$jar.metadata" ]; then
    sh "$root/scripts/ci-jar.sh" verify "$module"
else
    # 전체 스모크에 필요한 미변경 서비스의 JAR을 현재 커밋에서 생성합니다.
    task=$(printf '%s' "$module" | tr / :)
    sh "$root/scripts/run-ci-gradle.sh" ":$task:bootJar"
    sh "$root/scripts/ci-jar.sh" record "$module"
fi
