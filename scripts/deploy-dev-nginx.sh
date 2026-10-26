#!/bin/sh

set -eu

if [ "$#" -gt 3 ]; then
    echo "Usage: $0 [compose-file] [https-override] [env-file]" >&2
    exit 2
fi

compose_file=${1:-docker-compose.yml}
https_override=${2:-infra/docker/https.override.yml}
env_file=${3:-.env}

for file in "$compose_file" "$https_override" "$env_file"; do
    if [ ! -f "$file" ]; then
        echo "Required deployment file not found: $file" >&2
        exit 1
    fi
done

compose() {
    docker compose --env-file "$env_file" --file "$compose_file" --file "$https_override" "$@"
}

# 새 인증서·설정을 별도 컨테이너에서 먼저 검사합니다. 실패 시 기존 nginx는 유지합니다.
compose config --quiet
compose run --rm --no-deps --pull never nginx nginx -t
compose up -d --no-deps --no-build --pull never --wait --wait-timeout 60 nginx

# 파일 내용만 바뀌면 Compose가 재생성하지 않으므로 실행 중인 nginx도 reload합니다.
# 재생성된 컨테이너의 entrypoint가 master를 시작할 때까지 기다립니다.
attempt=0
# PID 조회는 호스트가 아닌 nginx 컨테이너 안에서 수행합니다.
# shellcheck disable=SC2016
until compose exec -T nginx sh -c 'test -s /var/run/nginx.pid && kill -0 "$(cat /var/run/nginx.pid)"'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 10 ]; then
        echo "Nginx master did not become ready." >&2
        exit 1
    fi
    sleep 1
done
compose exec -T nginx nginx -t
compose exec -T nginx nginx -s reload
