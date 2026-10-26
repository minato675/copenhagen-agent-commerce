#!/bin/sh
# 소스 빌드로 대체하지 않고 검증한 artifact만 작은 Docker context로 전달합니다.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
module=${1:?module path is required}
image=${2:?image tag is required}
sh "$root/scripts/ci-jar.sh" verify "$module"
context=$(mktemp -d -t copenhagen-ci-image-XXXXXXXX)
trap 'rm -rf "$context"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$context/$module/build/libs"
cp "$root/backend/$module/build/libs/app.jar" "$context/$module/build/libs/app.jar"
docker build --load --target ci-runtime \
    --file "$root/backend/$module/Dockerfile" \
    --label "org.opencontainers.image.revision=$CI_COMMIT_SHA" \
    --tag "$image" "$context"
