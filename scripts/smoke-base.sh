#!/bin/sh
# stdout에는 확정한 기준 SHA만 출력합니다. CI에서는 이동하는 브랜치를 조회하지 않습니다.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)

fail() {
    printf 'FAIL smoke baseline: %s\n' "$*" >&2
    exit 1
}

require_sha() {
    case "$2" in
        ''|*[!0-9a-fA-F]*) fail "$1 must be a full commit SHA" ;;
    esac
    [ "${#2}" -eq 40 ] || fail "$1 must be a full commit SHA"
    [ "$2" != 0000000000000000000000000000000000000000 ] ||
        fail "$1 is zero; no previous baseline is available"
}

candidate=$(git -C "$root" rev-parse --verify HEAD)
if [ "${CI:-false}" = true ] || [ -n "${CI_PIPELINE_SOURCE:-}" ]; then
    require_sha CI_COMMIT_SHA "${CI_COMMIT_SHA:-}"
    [ "$candidate" = "$CI_COMMIT_SHA" ] || fail 'checkout does not match CI_COMMIT_SHA'
    case "${CI_PIPELINE_SOURCE:-}" in
        merge_request_event)
            [ "${CI_MERGE_REQUEST_TARGET_BRANCH_NAME:-}" = develop ] ||
                fail 'only merge requests targeting develop are supported'
            case "${CI_MERGE_REQUEST_EVENT_TYPE:-detached}" in
                detached)
                    ref=${CI_MERGE_REQUEST_DIFF_BASE_SHA:-}
                    field=CI_MERGE_REQUEST_DIFF_BASE_SHA
                    ;;
                merged_result|merge_train)
                    ref=${CI_MERGE_REQUEST_TARGET_BRANCH_SHA:-}
                    field=CI_MERGE_REQUEST_TARGET_BRANCH_SHA
                    ;;
                *) fail 'unsupported merge request event type' ;;
            esac
            ;;
        push)
            [ "${CI_COMMIT_BRANCH:-}" = develop ] || fail 'only develop pushes are supported'
            ref=${CI_COMMIT_BEFORE_SHA:-}
            field=CI_COMMIT_BEFORE_SHA
            ;;
        *) fail 'unsupported CI pipeline source' ;;
    esac
    require_sha "$field" "$ref"
else
    # 로컬 수동 실행에서만 명시적인 ref와 기존 기본값을 허용합니다.
    ref=${SMOKE_BASE_REF:-origin/develop}
    field=SMOKE_BASE_REF
fi

base=$(git -C "$root" rev-parse --verify --end-of-options "$ref^{commit}" 2>/dev/null) ||
    fail "$field commit is unavailable; fetch the pinned commit or use GIT_DEPTH=0"
git -C "$root" merge-base --is-ancestor "$base" "$candidate" ||
    fail "$field is not an ancestor of the candidate"
printf '%s\n' "$base"
