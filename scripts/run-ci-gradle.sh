#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
case "${CI_GRADLE_MAX_WORKERS:-}" in
    '') printf '%s\n' 'Gradle workers: Gradle default (no CI override)' ;;
    *[!0-9]*|0*) echo 'CI_GRADLE_MAX_WORKERS must be a positive integer' >&2; exit 1 ;;
    *)
        printf 'Gradle workers: %s\n' "$CI_GRADLE_MAX_WORKERS"
        set -- "$@" "--max-workers=$CI_GRADLE_MAX_WORKERS"
        ;;
esac
cd "$root/backend"
exec ./gradlew "$@" --build-cache --no-daemon --console=plain
