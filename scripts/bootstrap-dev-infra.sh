#!/bin/sh

set -eu

compose_file=${1:-docker-compose.yml}
env_file=${2:-.env}

if [ ! -f "$compose_file" ]; then
    echo "Compose file not found: $compose_file" >&2
    exit 1
fi

if [ ! -f "$env_file" ]; then
    echo "Environment file not found: $env_file" >&2
    echo "Copy infra/docker/ec2.env.example to .env and replace every CHANGE_ME first." >&2
    exit 1
fi

if grep -q 'CHANGE_ME' "$env_file"; then
    echo "Replace every CHANGE_ME value in $env_file before starting the databases." >&2
    exit 1
fi

docker compose --env-file "$env_file" --file "$compose_file" pull \
    core-db \
    pay-db \
    merchant-db \
    redis
docker compose --env-file "$env_file" --file "$compose_file" up \
    -d \
    --wait \
    core-db \
    pay-db \
    merchant-db \
    redis
docker compose --env-file "$env_file" --file "$compose_file" ps \
    core-db \
    pay-db \
    merchant-db \
    redis
