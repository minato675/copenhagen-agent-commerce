#!/bin/sh

set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 6 ]; then
    echo "Usage: $0 <compose-service> <image-variable> <image> [compose-file] [wait-timeout] [compose-override]" >&2
    exit 2
fi

compose_service=$1
image_variable=$2
container_image=$3
compose_file=${4:-docker-compose.yml}
wait_timeout=${5:-120}
compose_override=${6:-}

case "$compose_service:$image_variable" in
    core-service:CORE_IMAGE|mcp-server:MCP_IMAGE|pay-service:PAY_IMAGE|ucp-merchant-mock:MERCHANT_MOCK_IMAGE|pg-mock:PG_MOCK_IMAGE)
        ;;
    *)
        echo "Unsupported service and image-variable pair: $compose_service:$image_variable" >&2
        exit 2
        ;;
esac

case "$wait_timeout" in
    ''|*[!0-9]*|0)
        echo "Wait timeout must be a positive integer." >&2
        exit 2
        ;;
esac

if [ ! -f "$compose_file" ]; then
    echo "Compose file not found: $compose_file" >&2
    exit 1
fi

if [ -n "$compose_override" ] && [ ! -f "$compose_override" ]; then
    echo "Compose override not found: $compose_override" >&2
    exit 1
fi

compose() {
    deployment_env=$1
    shift
    if [ -n "$compose_override" ]; then
        docker compose --env-file "$deployment_env" --file "$compose_file" --file "$compose_override" "$@"
    else
        docker compose --env-file "$deployment_env" --file "$compose_file" "$@"
    fi
}

env_file=.env
temporary_env="${env_file}.tmp.$$"
touch "$env_file"
chmod 600 "$env_file"
trap 'rm -f "$temporary_env"' 0 1 2 15

previous_image=$(awk -v key="$image_variable" '
    index($0, key "=") == 1 {
        print substr($0, length(key) + 2)
        exit
    }
' "$env_file")

docker image inspect "$container_image" >/dev/null

awk -v key="$image_variable" -v value="$container_image" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
        print key "=" value
        replaced = 1
        next
    }
    { print }
    END {
        if (!replaced) {
            print key "=" value
        }
    }
' "$env_file" > "$temporary_env"

chmod 600 "$temporary_env"

# 추가 진단에 포함된 .env 및 실행 환경의 비밀값을 정규식 해석 없이 치환합니다.
# 이 배포 스크립트가 기록하는 .env는 KEY=VALUE 형식입니다.
redact() {
    awk '
        function remember(key, value) {
            if (toupper(key) ~ /(PASSWORD|SECRET|TOKEN|API_KEY|PRIVATE_KEY|CREDENTIAL)/ && value != "") {
                if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) value = substr(value, 2, length(value) - 2)
                if (value != "") secrets[value] = 1
            }
        }
        BEGIN { for (key in ENVIRON) remember(key, ENVIRON[key]) }
        FILENAME != "-" {
            separator = index($0, "=")
            if (separator) remember(substr($0, 1, separator - 1), substr($0, separator + 1))
            next
        }
        {
            line = $0
            result = ""
            while (length(line)) {
                first = 0
                size = 0
                for (secret in secrets) {
                    position = index(line, secret)
                    if (position && (!first || position < first || (position == first && length(secret) > size))) {
                        first = position
                        size = length(secret)
                    }
                }
                if (!first) { result = result line; break }
                result = result substr(line, 1, first - 1) "[redacted]"
                line = substr(line, first + size)
            }
            print substr(result, 1, 4096)
        }
    ' "$env_file" -
}

diagnostics() {
    # 호출자는 실패를 무시합니다. inspect는 환경/설정 전체를 출력하지 않습니다.
    compose "$1" ps --all "$compose_service" nginx || true
    diagnostic_containers=$(compose "$1" \
        ps --all --quiet "$compose_service" nginx) || true
    for diagnostic_container in $diagnostic_containers; do
        docker inspect --format '{{.Name}} image={{.Config.Image}} status={{.State.Status}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}} error={{.State.Error}}{{if .State.Health}} health={{.State.Health.Status}}{{range .State.Health.Log}}{{println}}{{.End}} exit={{.ExitCode}} {{.Output}}{{end}}{{end}}' \
            "$diagnostic_container" || true
    done
    compose "$1" \
        logs --no-color --tail 100 "$compose_service" nginx || true
}

deploy_with_env() {
    deploy_step=startup-health
    compose "$1" up \
        -d \
        --no-deps \
        --no-build \
        --pull never \
        --wait \
        --wait-timeout "$wait_timeout" \
        "$compose_service" || return "$?"

    deploy_step=nginx-status
    running_nginx=$(compose "$1" \
        ps --status running --quiet nginx) || return "$?"
    if [ -n "$running_nginx" ]; then
        # 컨테이너 교체로 바뀐 upstream IP를 다시 읽습니다. 최초 배포는 Nginx 없이도 가능합니다.
        deploy_step=nginx-check
        compose "$1" exec -T nginx nginx -t || return "$?"
        deploy_step=nginx-reload
        compose "$1" exec -T nginx nginx -s reload || return "$?"
    fi
    return 0
}

if deploy_with_env "$temporary_env"; then
    mv "$temporary_env" "$env_file"
    compose "$env_file" ps "$compose_service"
else
    deploy_status=$?
    printf 'FAIL deploy service=%s image=%s step=%s exit=%s\n' \
        "$compose_service" "$container_image" "$deploy_step" "$deploy_status" | redact >&2 || true
    diagnostics "$temporary_env" 2>&1 | redact >&2 || true
    if [ -n "$previous_image" ] && docker image inspect "$previous_image" >/dev/null 2>&1; then
        echo "ROLLBACK attempting service=$compose_service" >&2
        if deploy_with_env "$env_file"; then
            echo "ROLLBACK SUCCESS service=$compose_service" >&2
        else
            rollback_status=$?
            echo "ROLLBACK FAILED service=$compose_service step=$deploy_step exit=$rollback_status" >&2
            diagnostics "$env_file" 2>&1 | redact >&2 || true
        fi
    else
        echo "ROLLBACK UNAVAILABLE service=$compose_service reason=previous-image-missing" >&2
    fi
    exit "$deploy_status"
fi
