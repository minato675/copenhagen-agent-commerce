#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
project="pay-observability-${RANDOM}-${RANDOM}"
work=$(mktemp -d -t copenhagen-pay-observability-XXXXXXXX)
env_file="$work/.env"
curl_image=${PAY_SMOKE_CURL_IMAGE:-docker.io/curlimages/curl:8.16.0}
request_id="synthetic-pay-request-001"
sensitive_token="synthetic-Authorization-token-cookie"
phase=prepare

cat > "$env_file" <<EOF
PAY_AUTH_USER_TOKENS_JSON={"synthetic-user":"synthetic-token"}
PAY_RECONCILIATION_ENABLED=false
PAY_NOTIFICATION_WORKER_ENABLED=false
PAY_FCM_ENABLED=false
EOF
chmod 600 "$env_file"

compose() {
    docker compose --project-name "$project" --env-file "$env_file" \
        --file "$root/docker-compose.yml" "$@"
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    if [ -f "$env_file" ]; then
        compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! command -v docker >/dev/null 2>&1; then
    echo "SKIP Docker CLI is not installed"
    exit 2
fi

phase=config
compose config --quiet
docker pull "$curl_image" >/dev/null

phase=deploy
compose up -d --build --wait --wait-timeout "${PAY_SMOKE_WAIT_TIMEOUT:-180}" \
    pay-db pg-mock pay-service >/dev/null

request_status() {
    local path=$1 extra_header=${2:-} response status
    local -a curl_args=(
        --silent
        --show-error
        --connect-timeout 5
        --max-time 10
        --header 'Accept: application/json'
        --header "X-Request-Id: $request_id"
        --header 'X-App-Version: synthetic-1.0.0'
        --header 'X-OS-Version: synthetic-os'
        --header 'X-Platform: SYNTHETIC'
        --dump-header -
        --output /dev/null
        --write-out '\n%{http_code}')
    if [ -n "$extra_header" ]; then
        curl_args+=(--header "$extra_header")
    fi
    response=$(docker run --rm --network "${project}_default" "$curl_image" \
        "${curl_args[@]}" \
        "http://pay-service:8080$path")
    status=$(printf '%s\n' "$response" | tail -n 1)
    if [ "$status" != "401" ]; then
        echo "FAIL path=$path expected=401 actual=$status"
        return 1
    fi
    if ! printf '%s\n' "$response" | grep -Eiq '^x-request-id:[[:space:]]*' ; then
        echo "FAIL path=$path response_request_id=missing"
        return 1
    fi
    echo "PASS path=$path status=$status response_request_id=present"
}

request_status /api/pay/v1/payment-approvals
request_status /api/pay/v1/payment-approvals "Authorization: Bearer $sensitive_token"

phase=logs
logs=$(compose logs --no-color --tail 300 pay-service)
start_count=$(printf '%s\n' "$logs" | grep -c 'pay.request.start' || true)
complete_count=$(printf '%s\n' "$logs" | grep -c 'pay.request.complete' || true)
if [ "$start_count" -lt 2 ] || [ "$complete_count" -lt 2 ]; then
    echo "FAIL phase=$phase request_start=$start_count request_complete=$complete_count"
    exit 1
fi
if ! printf '%s\n' "$logs" | grep -Fq "req=$request_id"; then
    echo "FAIL phase=$phase request_id=missing"
    exit 1
fi
if ! printf '%s\n' "$logs" | grep -Fq 'service=pay-service'; then
    echo "FAIL phase=$phase service=missing"
    exit 1
fi
if ! printf '%s\n' "$logs" | grep -Fq 'status=401'; then
    echo "FAIL phase=$phase status=missing"
    exit 1
fi
if printf '%s\n' "$logs" | grep -Fq "$sensitive_token"; then
    echo "FAIL phase=$phase sensitive_authorization_token=exposed"
    exit 1
fi
echo "PASS phase=$phase request_start=$start_count request_complete=$complete_count sensitive_values=masked"
