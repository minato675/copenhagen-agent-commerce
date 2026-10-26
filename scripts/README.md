# 공용 스크립트

## `analyze-mcp-timing.py`

기존 MCP timing 로그를 요청별로 묶고 도구·Core 왕복 시간을 집계합니다.
하위 타이머를 합산해 대기시간을 부풀리지 않으며 병렬 도구는 실행 구간 합집합으로 계산합니다.
수집 명령, 사용자 측정 항목, 해석 한계는 [지연 QA 절차](../docs/api/mcp-latency-qa-499.md)를 따릅니다.

```bash
python3 scripts/analyze-mcp-timing.py qa499.log --observed-seconds 80
python3 -m unittest discover -s scripts/tests -p test_mcp_timing.py
```

## `bootstrap-dev-infra.sh`

- 용도: 개발 EC2의 PostgreSQL 3개, Redis 최초 기동
- 기본 파일: `docker-compose.yml`, `.env`
- 중단 조건: `.env`에 `CHANGE_ME` 잔존
- 제외: 애플리케이션, Nginx

```bash
./scripts/bootstrap-dev-infra.sh [compose-file] [env-file]
```

## `deploy-dev-service.sh`

- 용도: GitLab deploy job의 단일 서비스 갱신
- 입력: 허용된 서비스와 이미지 변수 조합, EC2에 적재된 이미지
- 절차: 컨테이너 교체, health 확인, 실행 중인 Nginx 검사와 reload, `.env` 확정
- 실패 처리: 롤백 전 진단 출력, 이전 이미지 복구 시도, 최초 실패 코드 유지
- 롤백 결과: `SUCCESS`, `FAILED`, `UNAVAILABLE`
- 로컬 개발: `docker compose up --build`

```bash
./scripts/deploy-dev-service.sh \
  <compose-service> <image-variable> <image> [compose-file] [wait-timeout] [compose-override]
```

CI 개발 서버 배포는 마지막 인자로 `infra/docker/https.override.yml`을 전달합니다. 동일 override가 신규 배포, 진단, 이전 이미지 복구와 nginx reload에 적용됩니다. 마지막 인자를 생략하는 로컬·기존 스모크 호출은 이전과 동일합니다.

## `deploy-dev-nginx.sh`

개발 서버에서 HTTPS Compose 구성을 적용합니다. 기본 인자는 `docker-compose.yml`, `infra/docker/https.override.yml`, `.env`입니다. 별도 임시 컨테이너에서 인증서와 nginx 설정을 검사한 후 nginx만 기동/재생성하고 reload합니다. 백엔드 컨테이너는 교체하지 않습니다. 서버 최초 인증서 발급과 자동 갱신 준비는 [nginx 문서](../infra/nginx/README.md)를 따릅니다.

```bash
sh scripts/deploy-dev-nginx.sh [compose-file] [https-override] [env-file]
python3 scripts/test-nginx-config.py
```

## `deploy-smoke.sh`

- 범위: 빈 DB 기동, 고정 기준 버전에서 MR 또는 develop 후보 버전으로 갱신, 실패 이미지 복구, 임시 자원 정리
- 요구사항: Docker/BuildKit 및 Buildx Bake, Compose 2.24.4 이상, Bash, Git, jq, Python 3
- 결과 경로: `tmp/deploy-smoke/`
- 개인 `.env`, 개발 EC2 접근 없음
- 상세: [MR 배포 스모크 테스트](../infra/gitlab/README.md#mr-배포-스모크-테스트)

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

동일한 표준 builder를 사용하는 서비스는 `smoke-dockerfiles.py`가 임시 Dockerfile에서
Gradle `bootJar` 실행을 합치고 Buildx Bake로 BuildKit 계층을 공유합니다. 각 서비스의 런타임 단계는
원문 그대로 유지하며 알 수 없는 builder는 원본 Dockerfile을 사용합니다.
HTTP 검사는 전용 `smoke-http` 컨테이너를 재사용합니다. 기동 확인 주기만 스모크
override에서 단축하며 health 명령, 의존 관계와 기동 실패 대기 한도는 유지합니다.
`result.log`의 `TIMING` 행은 각 단계 및 자원 정리를 포함한 총 실행 시간을 기록합니다.
짧게 실행하는 스모크 앱과 표준 Temurin 21 공유 빌드에만 JVM C1 기동 옵션을 적용합니다.
CI는 `SMOKE_GRADLE_CACHE` 경로에 Gradle 캐시를 보관합니다. 로컬에서 같은 옵션을
지정하면 `caches/`, `wrapper/`를 Docker cache mount에 넣고 종료 전 다시 내보냅니다.
이 디렉터리는 재사용을 위해 남기며 앱 이미지·DB·환경 파일은 포함하지 않습니다.
`SMOKE_RUNTIME_CACHE`에는 JDK·JRE·Nginx·DB·Redis·curl 기반 이미지 tar를 저장합니다.
다음 실행에서 복원한 뒤 원격 태그를 확인합니다. CI 후보 앱 이미지는 같은 pipeline의
검증된 JAR artifact를 패키징하며, 기준 버전과 로컬 실행은 해당 소스를 빌드합니다.

## `pay-observability-smoke.sh`

- 범위: Pay DB·PG Mock·Pay만 격리 기동해 health, 인증 실패, `X-Request-Id` 응답·로그 연결과 합성 Authorization 값 비노출을 확인합니다.
- 기본 설정: 대사 Worker·알림 Worker·FCM 발송은 꺼서 외부 계정과 실제 결제를 사용하지 않습니다.
- 자원: 임시 Compose 프로젝트·네트워크·볼륨을 만들고 종료 시 함께 정리합니다.
- 실제 Firebase 수신·모바일 앱 로그·Nginx 연계는 이 스크립트의 범위가 아닙니다.

```bash
bash scripts/pay-observability-smoke.sh
```

## 실패 진단 검증

기준 SHA·CI 의존 관계 및 Docker CLI 대체 테스트. 요구사항: Python 3, PyYAML, Bash, Git, jq. Docker daemon, EC2 불필요. CI·스크립트 변경 시 `ci-config-test`에서도 실행합니다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
bash -n scripts/deploy-smoke.sh
sh -n scripts/smoke-base.sh
sh -n scripts/deploy-dev-service.sh
sh -n scripts/deploy-dev-nginx.sh
shellcheck scripts/smoke-base.sh scripts/deploy-smoke.sh scripts/deploy-dev-service.sh
shellcheck scripts/deploy-dev-nginx.sh
shellcheck scripts/ci-jar.sh scripts/build-ci-jar.sh scripts/build-ci-image.sh scripts/run-ci-gradle.sh
git diff --check
```

| 검증 항목 | 2026-09-10 결과 |
| --- | --- |
| 회귀 테스트 10개와 하위 시나리오 | 통과: 진단 순서, 종료 코드, HTTP 오류, Merchant 인증 전환, Nginx 오류, 롤백, `.env`, 비밀값 마스킹 |
| 문법, ShellCheck 0.10.0, diff 검사 | 통과 |
| 격리 컨테이너 배포 | 통과: 롤백 전 진단, 합성 비밀번호 마스킹, 이전 이미지 healthy 복구, `.env` 유지, 종료 코드 `1` |
| 전체 앱 스모크, GitLab Runner, EC2 | 미검증 |

격리 검증 환경: Podman API, Docker Compose. 테스트 컨테이너, 네트워크, 전용 이미지 정리 완료.

## CI 빌드 스크립트

| 스크립트 | 역할 |
| --- | --- |
| `run-ci-gradle.sh <task>...` | CI Gradle 실행. 선택한 `CI_GRADLE_MAX_WORKERS`를 검증해 전달 |
| `ci-jar.sh record\|verify <module>` | JAR의 SHA·pipeline·서비스·내용 기록 및 검증. `CI_COMMIT_SHA`, `CI_PIPELINE_ID` 필수 |
| `build-ci-jar.sh <module>` | 테스트 artifact 재사용. `REQUIRE_TEST_ARTIFACT=true`일 때 누락 시 실패. 미변경 스모크 서비스만 소스 빌드 |
| `build-ci-image.sh <module> <image>` | 검증된 JAR만 포함한 임시 context로 `ci-runtime` 이미지 빌드 |

module은 `apps/core-service`, `apps/mcp-server`, `apps/pay-service`, `mocks/ucp-merchant-mock`, `mocks/pg-mock`만 허용합니다.

2026-09-17 검증: 회귀 테스트 20개 통과. artifact 누락·오염·다른 SHA/pipeline/서비스 거부, Gradle 중복 실행 방지, worker 옵션, CI Job 의존 관계, 기존 실패 진단과 롤백 확인. 로컬 Podman에서 다섯 CI 이미지의 JAR 바이트와 런타임 설정 일치, Docker 소스 빌드의 worker 옵션 전달 확인. 실제 서버 성능 측정과 구분한 결과는 [Runner 측정 기록](../infra/gitlab/runner-performance.md)에 정리합니다.

## `smoke-base.sh`

- 용도: `deploy-smoke.sh`가 사용할 고정 기준 SHA 선택과 Git 이력 검증. stdout에는 SHA만 출력
- 일반 MR: `CI_MERGE_REQUEST_DIFF_BASE_SHA`, 병합 결과 MR: `CI_MERGE_REQUEST_TARGET_BRANCH_SHA`
- develop push: `CI_COMMIT_BEFORE_SHA`. 최초 push의 zero SHA는 실패 처리
- CI 후보: checkout `HEAD`가 `CI_COMMIT_SHA`와 일치해야 함
- 기준 누락·잘못된 SHA·없는 커밋·비조상 커밋: 실패. 이동하는 브랜치나 `HEAD^`로 대체하지 않음
- 로컬에서만 `SMOKE_BASE_REF` 사용 가능. CI에서는 override를 무시하고 pipeline 기준을 사용
- 기준 커밋 선택은 DB schema 원복 또는 실제 EC2 배포 버전 일치를 보장하지 않음

2026-09-17 검증: 로컬 회귀 테스트 24개 통과. 실제 Git 이력으로 동시 머지·Retry·다중 커밋 push와 잘못된 기준을 재현했고, 변경 경로별 필수 smoke 및 배포 의존 관계를 검사했습니다. Docker CLI 대체 테스트 결과이며 실제 GitLab·EC2 실행과 구분합니다.
