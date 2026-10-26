#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d -t copenhagen-smoke-XXXXXXXX)
export COMPOSE_PROJECT_NAME
COMPOSE_PROJECT_NAME=$(basename "$work" | tr '[:upper:]' '[:lower:]')
results=${SMOKE_ARTIFACT_DIR:-$root/tmp/deploy-smoke}
mkdir -p "$results"
results=$(cd "$results" && pwd)
: > "$results/result.log"
wait_timeout=${SMOKE_WAIT_TIMEOUT:-180}
curl_image=docker.io/curlimages/curl:8.16.0
services=(pg-mock pay-service ucp-merchant-mock core-service mcp-server)
image_variables=(PG_MOCK_IMAGE PAY_IMAGE MERCHANT_MOCK_IMAGE CORE_IMAGE MCP_IMAGE)
images=()
gradle_cache=${SMOKE_GRADLE_CACHE:-}
runtime_cache=${SMOKE_RUNTIME_CACHE:-}
runtime_images=()
phase=prepare
phase_started=$SECONDS
started=$SECONDS

# 개인 .env와 기존 배포 이미지 설정을 사용하지 않습니다.
unset PG_MOCK_IMAGE PAY_IMAGE MERCHANT_MOCK_IMAGE CORE_IMAGE MCP_IMAGE SMOKE_NGINX_IMAGE
unset CORE_DB_USER CORE_DB_PASSWORD PAY_DB_USER PAY_DB_PASSWORD MERCHANT_DB_USER MERCHANT_DB_PASSWORD
password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
printf 'CORE_DB_PASSWORD=%s\nPAY_DB_PASSWORD=%s\nMERCHANT_DB_PASSWORD=%s\n' \
    "$password" "$password" "$password" > "$work/settings.env"
chmod 600 "$work/settings.env"

compose() {
    docker compose --env-file "$work/.env" --file "$work/compose.yml" "$@"
}

record() {
    printf '%s\n' "$*" | tee -a "$results/result.log"
}

set_phase() {
    record "TIMING phase=$phase seconds=$((SECONDS - phase_started))"
    phase=$1
    phase_started=$SECONDS
    record "START phase=$phase"
}

snapshot() {
    if [ -f "$work/compose.yml" ]; then
        compose ps --all 2>&1 | sed "s/$password/[redacted]/g" \
            > "$results/$phase-containers.txt" || true
        compose logs --no-color --tail 200 2>&1 \
            | sed "s/$password/[redacted]/g" | cut -c 1-4096 \
            > "$results/$phase-containers.log" || true
        : > "$results/$phase-health.log"
        local containers container
        containers=$(compose ps --all --quiet 2>/dev/null) || true
        for container in $containers; do
            docker inspect --format '{{.Name}} status={{.State.Status}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}} error={{.State.Error}}{{if .State.Health}} health={{.State.Health.Status}}{{range .State.Health.Log}}{{println}}{{.End}} exit={{.ExitCode}} {{.Output}}{{end}}{{end}}' "$container" 2>&1 \
                | sed "s/$password/[redacted]/g" | cut -c 1-4096 \
                >> "$results/$phase-health.log" || true
        done
    fi
}

cleanup() {
    local status=$? file cleanup_started=$SECONDS
    trap - EXIT
    # 진단 실패 때문에 원래 종료 코드나 자원 정리가 달라지지 않게 합니다.
    set +e
    record "TIMING phase=$phase seconds=$((SECONDS - phase_started))"
    if [ "$status" -ne 0 ]; then
        record "FAIL exit=$status phase=$phase"
    fi
    snapshot
    if [ "$status" -ne 0 ]; then
        for file in "$results/$phase-containers.txt" "$results/$phase-health.log" "$results/$phase-containers.log"; do
            if [ -f "$file" ]; then
                printf '\n--- %s ---\n' "${file##*/}"
                cat "$file"
            fi
        done
    fi
    if [ -f "$work/compose.yml" ]; then
        if ! compose down --volumes --remove-orphans; then
            record "FAIL cleanup phase=$phase"
            if [ "$status" -eq 0 ]; then status=1; fi
        fi
    fi
    if [ "${#images[@]}" -gt 0 ]; then
        docker image rm "${images[@]}" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
    record "exit=$status phase=$phase"
    record "TIMING phase=cleanup seconds=$((SECONDS - cleanup_started))"
    record "TIMING total_seconds=$((SECONDS - started))"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

prepare_gradle_cache() {
    [ -n "$gradle_cache" ] || return 0
    mkdir -p "$gradle_cache"
    if [ -d "$gradle_cache/wrapper" ]; then
        record 'CACHE gradle=restored'
    else
        record 'CACHE gradle=empty'
    fi
    images+=("$COMPOSE_PROJECT_NAME/gradle-cache:seed")
    # A unique token executes the copy without clearing BuildKit cache mounts.
    docker build --build-arg "SMOKE_CACHE_RUN=$COMPOSE_PROJECT_NAME-seed" --target seed \
        --tag "$COMPOSE_PROJECT_NAME/gradle-cache:seed" \
        --file "$root/infra/docker/smoke-gradle-cache.Dockerfile" "$gradle_cache"
}

save_gradle_cache() {
    [ -n "$gradle_cache" ] || return 0
    set_phase cache-export
    docker build --build-arg "SMOKE_CACHE_RUN=$COMPOSE_PROJECT_NAME-export" --target export \
        --output "type=local,dest=$gradle_cache" \
        --file "$root/infra/docker/smoke-gradle-cache.Dockerfile" "$gradle_cache"
}

restore_runtime_cache() {
    [ -n "$runtime_cache" ] || return 0
    mkdir -p "$runtime_cache"
    if [ -f "$runtime_cache/images.tar" ]; then
        record 'CACHE runtime=restored'
        docker image load --input "$runtime_cache/images.tar"
    else
        record 'CACHE runtime=empty'
    fi
}

refresh_runtime_images() {
    [ -n "$runtime_cache" ] || return 0
    local refs image
    refs=$(compose config --format json | jq -er \
        '[.services | to_entries[] | select(.key == "core-db" or .key == "pay-db" or .key == "merchant-db" or .key == "redis" or .key == "smoke-http") | .value.image] | unique[]')
    while IFS= read -r image; do runtime_images+=("$image"); done <<< "$refs"
    # Check mutable upstream tags; restored layers avoid downloading them again.
    compose pull --quiet core-db pay-db merchant-db redis smoke-http
}

cache_base_image() {
    [ -n "$runtime_cache" ] || return 0
    local image=$1 previous
    for previous in "${runtime_images[@]}"; do
        [ "$previous" != "$image" ] || return 0
    done
    docker pull "$image"
    runtime_images+=("$image")
}

save_runtime_cache() {
    [ -n "$runtime_cache" ] || return 0
    set_phase runtime-cache-export
    local unique_images=()
    mapfile -t unique_images < <(printf '%s\n' "${runtime_images[@]}" | sort -u)
    docker image save --output "$runtime_cache/images.tar" "${unique_images[@]}"
}

build_images() {
    local source=$1 tag=$2 service image nginx_image module
    set_phase "build-$tag"
    python3 "$root/scripts/smoke-dockerfiles.py" "$source" "$work/dockerfiles-$tag" \
        "$COMPOSE_PROJECT_NAME" "$tag"
    while IFS= read -r image; do
        [ -z "$image" ] || cache_base_image "$image"
    done < "$work/dockerfiles-$tag/base-images.txt"
    for service in "${services[@]}"; do
        images+=("$COMPOSE_PROJECT_NAME/$service:$tag")
    done
    if [ "$tag" = candidate ] && [ "${SMOKE_USE_CI_ARTIFACTS:-false}" = true ]; then
        for service in "${services[@]}"; do
            case "$service" in
                pg-mock|ucp-merchant-mock) module="mocks/$service" ;;
                *) module="apps/$service" ;;
            esac
            sh "$root/scripts/build-ci-image.sh" "$module" "$COMPOSE_PROJECT_NAME/$service:$tag"
        done
    else
        docker buildx bake --load --progress plain \
            --allow "fs.read=$source/backend" --allow "fs.read=$work/dockerfiles-$tag" \
            --set "*.args.GRADLE_MAX_WORKERS=${CI_GRADLE_MAX_WORKERS:-}" \
            --file "$work/dockerfiles-$tag/bake.json"
    fi
    images+=("$COMPOSE_PROJECT_NAME/nginx:$tag")
    # 실제 Compose와 동일한 Nginx image 및 설정을 사용합니다.
    nginx_image=$(docker compose --env-file "$work/settings.env" --file "$source/docker-compose.yml" config --format json | jq -er '.services.nginx.image')
    cache_base_image "$nginx_image"
    docker build --load --tag "$COMPOSE_PROJECT_NAME/nginx:$tag" --file - "$source/infra/nginx" <<EOF
FROM $nginx_image
COPY nginx.conf /etc/nginx/nginx.conf
EOF
}

configure() {
    local source=$1 tag=$2 index
    cp "$work/settings.env" "$work/.env"
    for index in "${!services[@]}"; do
        printf '%s=%s/%s:%s\n' "${image_variables[$index]}" \
            "$COMPOSE_PROJECT_NAME" "${services[$index]}" "$tag" >> "$work/.env"
    done
    printf 'SMOKE_NGINX_IMAGE=%s/nginx:%s\n' "$COMPOSE_PROJECT_NAME" "$tag" >> "$work/.env"
    render_compose "$source"
}

render_compose() {
    local source=$1
    docker compose --env-file "$work/.env" \
        --file "$source/docker-compose.yml" --file "$root/infra/docker/smoke.override.yml" \
        config --no-interpolate > "$work/compose.yml"
}

expect_http() {
    local url=$1 expected=$2 assertion=${3:-} response code body status reason
    if response=$(compose exec -T smoke-http curl \
        --silent --connect-timeout 5 --max-time 10 \
        --header 'Accept: application/json' --write-out '\n%{http_code}' "$url" 2>/dev/null); then
        :
    else
        status=$?
        case "$status" in
            6) reason=dns ;;
            7) reason=connection ;;
            28) reason=timeout ;;
            *) reason=transport ;;
        esac
        record "FAIL HTTP target=$url reason=$reason exit=$status connect-timeout=5 max-time=10"
        return "$status"
    fi
    code=${response##*$'\n'}
    body=${response%$'\n'*}
    if [ "$code" != "$expected" ]; then
        record "FAIL HTTP target=$url reason=status expected=$expected actual=$code"
        return 1
    fi
    if [ -n "$assertion" ]; then
        if ! printf '%s' "$body" | jq -e "$assertion" >/dev/null 2>&1; then
            record "FAIL HTTP target=$url reason=json assertion=$assertion"
            return 1
        fi
    fi
    record "PASS $url status=$code"
}

expect_dependency_http() {
    local source=$1 variable=$2 path=$3 assertion=$4 response status
    # URL 값과 응답 본문은 로그에 남기지 않습니다.
    # shellcheck disable=SC2016
    if response=$(compose exec -T "$source" sh -c \
        'wget -T 10 -qO- "$(printenv "$1")$2"' sh "$variable" "$path" 2>/dev/null); then
        :
    else
        status=$?
        record "FAIL HTTP source=$source target=$variable$path reason=request exit=$status timeout=10"
        return "$status"
    fi
    if ! printf '%s' "$response" | jq -e "$assertion" >/dev/null 2>&1; then
        record "FAIL HTTP source=$source target=$variable$path reason=json assertion=$assertion"
        return 1
    fi
    record "PASS $source -> $variable$path"
}

check_http() {
    local merchant_status=${1:?merchant smoke status is required} service path source variable
    for service in "${services[@]}"; do
        expect_http "http://$service:8080/actuator/health" 200 '.status == "UP"'
    done
    compose exec -T nginx nginx -t
    # 공개 경로는 upstream에 도달한 JSON 404인지 확인합니다.
    # Nginx 자체 404나 502를 정상 응답으로 인정하지 않습니다.
    for path in /api/core/__smoke__ /api/pay/__smoke__ /mcp/__smoke__; do
        expect_http "http://nginx$path" 404 ".status == 404 and .path == \"$path\""
    done
    # Merchant 인증 경계는 Pay 버전에 따라 달라지므로 배포 단계의 기대값을 명시합니다.
    case "$merchant_status" in
        401)
            expect_http http://nginx/merchant/v1/__smoke__ 401 \
                '.errorCode == "AUTHENTICATION_REQUIRED"'
            ;;
        404)
            expect_http http://nginx/merchant/v1/__smoke__ 404 \
                '.status == 404 and .path == "/merchant/v1/__smoke__"'
            ;;
        *)
            record "FAIL unsupported Merchant smoke status=$merchant_status"
            return 1
            ;;
    esac
    # 내부 API는 Nginx에서 외부 노출을 차단합니다.
    expect_http http://nginx/internal/v1/__smoke__ 404
    for source in core-service mcp-server pay-service ucp-merchant-mock; do
        case "$source" in
            core-service|ucp-merchant-mock) variable=PAY_SERVICE_URL ;;
            mcp-server) variable=CORE_SERVICE_URL ;;
            pay-service) variable=PG_SERVICE_URL ;;
        esac
        expect_dependency_http "$source" "$variable" /actuator/health '.status == "UP"'
    done
    expect_dependency_http core-service MERCHANT_SERVICE_URL /.well-known/ucp \
        '.ucp.services["dev.ucp.shopping"][0].transport == "rest" and (.keys | length > 0)'
}

database_sql() {
    # shellcheck disable=SC2016
    compose exec -T "$1" sh -c \
        'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}

check_data() {
    local database marker
    for database in core-db pay-db merchant-db; do
        marker=$(printf 'SELECT id FROM deploy_smoke.marker;\n' | database_sql "$database")
        if [ "$marker" != "$COMPOSE_PROJECT_NAME" ]; then
            record "FAIL $database persisted data"
            return 1
        fi
        record "PASS $database persisted data"
    done
}

base_sha=$(sh "$root/scripts/smoke-base.sh")
record "base=$base_sha candidate=$(git -C "$root" rev-parse HEAD) project=$COMPOSE_PROJECT_NAME"
if [ "${SMOKE_USE_CI_ARTIFACTS:-false}" = true ]; then
    for service in "${services[@]}"; do
        case "$service" in
            pg-mock|ucp-merchant-mock) module="mocks/$service" ;;
            *) module="apps/$service" ;;
        esac
        sh "$root/scripts/ci-jar.sh" verify "$module"
    done
fi
docker info >/dev/null
restore_runtime_cache
prepare_gradle_cache
docker pull "$curl_image"
candidate_source=$root
if [ "${CI:-false}" = true ] || [ -n "${CI_PIPELINE_SOURCE:-}" ]; then
    # Archive both commits consistently: runner checkout permissions can differ
    # from git archive and invalidate identical backend build contexts.
    candidate_source=$work/candidate
    mkdir "$candidate_source"
    git -C "$root" archive HEAD | tar -x -C "$candidate_source"
fi
build_images "$candidate_source" candidate
configure "$root" candidate
set_phase fresh-deploy
refresh_runtime_images
compose up -d --no-build --wait --wait-timeout "$wait_timeout"
check_http 401
record 'PASS fresh deployment'
snapshot
compose down --volumes --remove-orphans

mkdir "$work/base"
git -C "$root" archive "$base_sha" | tar -x -C "$work/base"
build_images "$work/base" base
configure "$work/base" base
set_phase baseline-deploy
refresh_runtime_images
compose up -d --no-build --wait --wait-timeout "$wait_timeout"
# develop 기준 이미지에도 Pay 서비스 간 인증 정책이 포함되어 있어 Merchant 경로는 401이어야 한다.
check_http 401
for database in core-db pay-db merchant-db; do
    database_sql "$database" <<SQL
CREATE SCHEMA deploy_smoke;
CREATE TABLE deploy_smoke.marker (id text PRIMARY KEY);
INSERT INTO deploy_smoke.marker VALUES ('$COMPOSE_PROJECT_NAME');
SQL
done
snapshot

# 기준 image의 .env와 DB를 유지하며 실제 배포처럼 서비스별로 갱신합니다.
render_compose "$root"
merchant_status=401
for index in "${!services[@]}"; do
    set_phase "upgrade-${services[$index]}"
    (cd "$work" && sh "$root/scripts/deploy-dev-service.sh" \
        "${services[$index]}" "${image_variables[$index]}" \
        "$COMPOSE_PROJECT_NAME/${services[$index]}:candidate" "$work/compose.yml" "$wait_timeout")
    if [ "${services[$index]}" = pay-service ]; then
        merchant_status=401
    fi
    check_http "$merchant_status"
    check_data
done
sed -i "s|^SMOKE_NGINX_IMAGE=.*|SMOKE_NGINX_IMAGE=$COMPOSE_PROJECT_NAME/nginx:candidate|" "$work/.env"
compose up -d --no-deps --no-build --pull never --wait --wait-timeout "$wait_timeout" nginx
set_phase upgrade-complete
check_http 401
snapshot
record 'PASS upgrade deployment'

set_phase rollback
images+=("$COMPOSE_PROJECT_NAME/core-service:broken")
docker build --load --tag "$COMPOSE_PROJECT_NAME/core-service:broken" - <<EOF
FROM docker.io/library/alpine:3.22
ENTRYPOINT ["/bin/sh", "-c", "exit 1"]
EOF
cp "$work/.env" "$work/previous.env"
if (cd "$work" && sh "$root/scripts/deploy-dev-service.sh" \
        core-service CORE_IMAGE "$COMPOSE_PROJECT_NAME/core-service:broken" "$work/compose.yml" 45); then
    record 'FAIL broken image deployment was accepted'
    exit 1
fi
cmp "$work/previous.env" "$work/.env"
restored=$(docker inspect --format '{{.Image}}' "$(compose ps --quiet core-service)")
[ "$restored" = "$(docker image inspect --format '{{.Id}}' "$COMPOSE_PROJECT_NAME/core-service:candidate")" ]
check_http 401
check_data
record 'PASS failed deployment restored the previous image and environment'
save_gradle_cache
save_runtime_cache
