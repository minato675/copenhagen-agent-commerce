# GitLab CI/CD와 EC2 직접 배포

- 배포 대상: 개발 EC2. production 배포 미정의
- 이미지 전달: `docker save | gzip | SSH | gunzip | docker load`
- Container Registry, 이미지 artifact 미사용
- 전송 단위: commit SHA 태그의 전체 이미지

## 실행 조건

| 이벤트 | lint/test/build | deploy-smoke | EC2 배포 |
| --- | --- | --- | --- |
| `develop` 대상 MR 생성, 갱신 | 변경 영향 job | Backend 또는 배포 설정 변경 시 전체 스택 | 없음 |
| `develop` push, MR merge 포함 | 변경 영향 job | Backend 또는 배포 설정 변경 시 전체 스택 | 스모크 성공 후 변경 영향 서비스 |
| 다른 브랜치 push | pipeline 없음 | 없음 | 없음 |

Stage: `lint -> test -> build -> deploy-smoke -> docker-build -> deploy-dev`

## JAR 생성과 이미지 전달

변경 영향 서비스의 `*-test`는 `test`와 `bootJar`를 한 번의 Gradle 실행으로 처리합니다. 성공한 Job만 `app.jar`와 provenance 파일을 artifact로 전달합니다. `*-build`는 이를 검증해 다음 단계로 전달하며 Gradle을 다시 실행하지 않습니다.

MR과 develop 스모크에는 다섯 서비스가 모두 필요합니다. 변경이 없어 테스트가 선택되지 않은 서비스만 `*-build`에서 현재 커밋의 `bootJar`를 실행합니다. 테스트가 선택된 서비스는 `REQUIRE_TEST_ARTIFACT=true`이며, artifact가 없거나 검증에 실패하면 소스 빌드로 대체하지 않습니다.

- 검증: 커밋 SHA, pipeline ID, 서비스 경로, JAR SHA-256
- 전달 경로: `backend/<module>/build/libs/app.jar` 및 `app.jar.metadata`
- 소비자: 같은 pipeline의 `deploy-smoke`, 서비스별 `*-docker-build`
- 이미지: `scripts/build-ci-image.sh`가 검증한 JAR만 임시 context에 복사하고 `ci-runtime` target 빌드
- 런타임: 기존 소스 빌드와 동일한 Dockerfile의 `runtime-base` 공유
- 로컬 `docker compose up --build`: 마지막 `runtime` target에서 기존 소스 빌드 유지
- 필수 회귀 검사: `ci-config-test`에서 CI 선택 규칙, artifact 실패 조건, worker 전달, 배포 진단·정리·복구 검사

`CI_GRADLE_MAX_WORKERS`는 기본값이 빈 문자열입니다. 비교할 pipeline에서 양의 정수를 지정하면 CI Gradle과 지원하는 Docker 소스 빌드에 전달됩니다. Runner 동시 실행 수나 컨테이너 CPU 제한을 변경하는 변수가 아닙니다. [Runner 측정 기록과 적용·원복 절차](runner-performance.md)를 참고하세요.

여러 Runner가 등록돼 있으면 수동 MR pipeline 실행 시 `CI_TARGET_RUNNER_TAG`에 측정 대상 Runner만 가진 태그를 지정할 수 있습니다. 해당 실행의 모든 Job이 그 태그를 요구합니다. 변수를 생략하거나 빈 값으로 두면 기존 태그 없는 Job 선택 방식을 유지합니다. 대상 Runner에 실제로 등록된 태그를 사용하고, Job 상세 화면의 Runner ID도 확인합니다. 태그가 일치하는 Runner가 없으면 Job은 대기하므로 임시 태그는 측정 Job이 모두 끝난 뒤 제거합니다.


`deploy-smoke`는 앞선 stage의 선택된 검증이 모두 성공한 뒤 실행합니다. 모든 서비스의 `*-docker-build`와 `*-deploy-dev`는 `deploy-smoke`를 필수 `needs`로 지정합니다. 스모크가 실패·취소되면 이미지 전송과 EC2 배포를 진행하지 않으며, 필수 Job이 누락되면 pipeline 생성이 실패합니다. `optional: true`나 `allow_failure`로 우회하지 않습니다.

CI·배포 스크립트 변경 시 `ci-config-test`가 기준 SHA 선택, Job 선택·의존 관계와 기존 실패 진단·복구 회귀 테스트 및 ShellCheck를 실행합니다.

## MR 배포 스모크 테스트

- 실행: 앞선 stage 성공 후 `smoke.gitlab-ci.yml`의 `deploy-smoke` job
- 스크립트: `scripts/deploy-smoke.sh`
- 대상: Core, Pay, MCP, Merchant Mock, PG Mock, PostgreSQL 3개, Redis, Nginx
- 구성: 후보 버전은 CI JAR artifact와 `ci-runtime`, 기준 버전은 해당 SHA의 소스 Dockerfile 사용. `docker-compose.yml` 재사용
- 격리: 실행별 Compose 프로젝트, 이미지 태그, 임시 DB 비밀번호
- 접근 제외: 개인 `.env`, 개발 EC2 키, 실제 DB
- Nginx: smoke override로 호스트 포트와 bind mount 제거, 원본 이미지에 동일 `nginx.conf` 복사

검증 순서:

1. MR 이미지 빌드, 빈 DB 기동, HTTP 검사
2. 임시 볼륨 초기화, 고정한 기준 SHA 버전 기동, 표식 데이터 생성
3. DB 유지 상태에서 MR 이미지 순차 교체, HTTP와 데이터 검사
4. 실패 이미지 배포, 이전 이미지 복구 검사
5. 로그 수집, 임시 자원 정리

| 항목 | 검증 기준 |
| --- | --- |
| 서비스 | health 응답, 실제 서비스 URL 호출, Core의 Merchant Profile 조회 |
| Nginx | 설정 검사, upstream 경로, 교체 후 reload |
| `__smoke__` 경로 | 공개 경로는 애플리케이션 JSON 404와 원본 path 일치. Merchant 경로는 기준·후보·업그레이드 전후 모두 무토큰 JSON 401 확인. 내부 경로는 Nginx 차단 404, 502 실패 |
| DB | 표식 데이터 유지, Flyway 기동 검사 |
| 복구 | `deploy-dev-service.sh` 실행 후 이전 이미지, `.env`, health, HTTP 확인 |

검증 제외: 결제 전체 E2E, 업무 데이터의 전체 마이그레이션 호환성, DB schema 원복, 실제 EC2의 SSH 권한과 자원 상태, 배포된 서비스별 SHA 일치.

변경 감지 경로: `backend/**/*`, 루트 CI/Compose, `infra/gitlab/**/*`, `infra/nginx/**/*`, smoke override, 스모크/기준 SHA 선택/배포 및 CI JAR·이미지·Gradle 스크립트. 해당 경로의 README 포함. 그 외 문서 변경은 생략. MR과 develop push에 동일한 경로를 적용합니다.

### Git 버전

`scripts/smoke-base.sh`가 pipeline의 고정된 커밋 정보를 선택합니다. CI에서 최신 대상 브랜치를 fetch하거나 `FETCH_HEAD`를 기준으로 사용하지 않습니다.

| 실행 | 기준 SHA | 후보 SHA |
| --- | --- | --- |
| 일반 MR (`detached`) | `CI_MERGE_REQUEST_DIFF_BASE_SHA` | `CI_COMMIT_SHA` |
| Merged results / Merge train | `CI_MERGE_REQUEST_TARGET_BRANCH_SHA` | `CI_COMMIT_SHA` |
| develop push | `CI_COMMIT_BEFORE_SHA` | `CI_COMMIT_SHA` |
| 로컬 실행 | `SMOKE_BASE_REF`, 미지정 시 `origin/develop` | 현재 `HEAD` |

- CI에서는 전체 40자리 SHA, 실제 checkout과 후보 SHA 일치, 기준 커밋 존재 및 기준이 후보의 조상인지 확인합니다. 실패하면 Docker 실행 전에 종료합니다.
- 최초 push의 zero SHA, 누락·잘못된 SHA, 비조상 기준은 실패 처리합니다. 현재 HEAD의 부모나 최신 develop으로 대체하지 않습니다. 원인을 확인하고 올바른 이력을 갖춘 pipeline에서 다시 검증합니다.
- `GIT_DEPTH=0`으로 고정 기준 커밋의 이력을 확보합니다. CI에서는 로컬용 `SMOKE_BASE_REF` override를 사용하지 않습니다.
- 동일 pipeline의 Job Retry는 같은 기준·후보를 검사합니다. 다른 MR의 develop 머지만으로 rebase할 필요는 없습니다. MR 코드 수정·실제 merge conflict 해결 후에는 새 커밋으로 검증합니다.
- MR의 고정 기준이 오래되어 현재 스모크 구성과 호환되지 않으면 기준 버전 자체의 실패 원인을 확인하고 한 번 동기화합니다. Git 이력 관계와 애플리케이션 호환성은 서로 다른 검사입니다.
- develop push는 push 직전 커밋에서 해당 push의 최종 커밋으로 교체합니다. 여러 커밋이 포함된 push에서도 `HEAD^`로 축소하지 않습니다. 직전 develop SHA가 실제 EC2의 서비스별 배포 SHA와 같다는 보장은 없습니다.

MR 스모크는 해당 MR 자체를 고정된 기준에서 검증합니다. 다른 MR과 결합했을 때만 발생하는 문제는 develop 병합 후 배포 전 스모크에서 발견될 수 있습니다. 이 경우 배포는 차단되지만 develop 코드에는 후속 수정 또는 revert가 필요합니다. 최신 develop과의 통합을 항상 병합 전에 보장하는 구조는 아닙니다.

2026-09-17 확인한 SSAFY GitLab은 Community Edition v18.11.5이며 Merged results pipelines와 Merge trains를 사용할 수 없습니다. 향후 해당 기능을 사용할 경우에도 위의 고정 대상 SHA 경로를 유지합니다.

### 실행 환경과 결과

| 항목 | 설정 |
| --- | --- |
| Runner | privileged Docker-in-Docker, 전체 스택 실행 메모리 |
| 도구 | BuildKit·Buildx Bake, Compose 2.24.4 이상. Bash, Git, jq, Python 3는 job에서 설치 |
| 최초 빌드 | 기준 버전 소스 빌드, MR 버전 JAR artifact 패키징, 필요한 이미지 다운로드 |
| Gradle 캐시 | `smoke-gradle-v1-$CI_COMMIT_REF_SLUG`, develop fallback. `tmp/smoke-gradle-cache/` 재사용 |
| 기반 이미지 캐시 | 같은 키의 `tmp/smoke-runtime-cache/`. 원격 태그 갱신 확인 후 계층 재사용 |
| Job 제한 | 30분 |
| 기동 대기 | `SMOKE_WAIT_TIMEOUT`, 기본 180초 |
| 결과 경로 | `SMOKE_ARTIFACT_DIR`, 기본 `tmp/deploy-smoke/`, Git 제외 |
| Artifact | 성공과 실패 모두 1일 보관. SHA, 단계별 판정, 상태, 로그, health |

```bash
git fetch origin develop
SMOKE_BASE_REF=origin/develop bash scripts/deploy-smoke.sh
```

종료, 실패, 종료 신호 수신 시 테스트 컨테이너, 네트워크, 볼륨, 이미지 정리. Runner 강제 종료 시 job 전용 Docker-in-Docker 폐기. 공유 daemon 전체 prune 금지.

기준 버전과 로컬 소스 빌드에서 동일한 builder는 한 Gradle 실행을 공유합니다.
CI 후보는 같은 pipeline에서 검증한 JAR artifact를 패키징하며 소스로 대체하지 않습니다.
스모크 앱과 표준 Temurin 21 공유 빌드는 C1 JVM 기동 옵션을 사용합니다.
검증 항목과 [측정 조건·결과](smoke-performance.md)는 별도로 기록합니다.

### 실패 진단

- 출력 순서: 실패 단계와 최초 종료 코드, 상태, health, 최근 로그, 자원 정리
- 성공 시 상세 진단: artifact에만 저장
- 진단 수집 실패: 정리 계속 진행
- 정리 추가 실패: 최초 실패 코드 유지
- 마스킹: 임시 DB 비밀번호 치환 후 파일 저장과 Job 출력
- 로그 제한: 컨테이너별 최근 200줄, 줄당 4096자. health도 줄당 4096자
- 출력 제외: 전체 inspect, 환경 변수 목록, `.env`, HTTP 응답 본문

로그 예시:

```text
FAIL exit=1 phase=fresh-deploy
--- fresh-deploy-containers.txt ---
--- fresh-deploy-health.log ---
/pay-service status=restarting ExitCode=1 OOMKilled=false error= health=unhealthy
--- fresh-deploy-containers.log ---
exit=1 phase=fresh-deploy
```

| HTTP 호출 | 실패 분류 | 표시 항목 |
| --- | --- | --- |
| 직접 curl | `dns`, `connection`, `timeout`, `transport`, `status`, `json` | 대상, 종료 코드 또는 기대/실제 상태 코드, 시간 제한 또는 JSON 조건 |
| 서비스 간 wget | `request`, `json` | 출발 서비스, 대상 URL 변수와 경로, 종료 코드와 시간 제한 또는 JSON 조건 |

```text
FAIL HTTP target=http://pay-service:8080/actuator/health reason=timeout exit=28 connect-timeout=5 max-time=10
FAIL HTTP target=http://nginx/api/pay/__smoke__ reason=status expected=404 actual=502
FAIL HTTP source=core-service target=PAY_SERVICE_URL/actuator/health reason=json assertion=.status == "UP"
```

### Merge 차단과 검증 범위

- 필수 설정: `allow_failure: false`, GitLab `Pipelines must succeed`. MR 자체 검증 실패는 Merge 차단, develop 스모크 실패는 EC2 배포 차단
- 실패 주입: 임시 브랜치에서 Dockerfile 빌드 오류, unhealthy 서비스, 잘못된 Nginx upstream별 job 실패와 merge 차단 확인
- Nginx 검증: 기준 Nginx에서 서비스 교체와 reload 후 MR Nginx 이미지로 재생성
- Nginx 설정만 변경: 스모크·TLS 검증 후 `nginx-deploy-dev`로 EC2 반영
- 실제 서버 검증: 독립된 스모크용 EC2 사용

## 서비스별 독립 job

| 서비스 | 대표 변경 경로 | EC2 이미지 |
| --- | --- | --- |
| Core | `backend/apps/core-service/**`, `ucp-contract`, `internal-api-contract` | `copenhagen/core-service:<commit-sha>` |
| MCP | `backend/apps/mcp-server/**`, `internal-api-contract` | `copenhagen/mcp-server:<commit-sha>` |
| Pay | `backend/apps/pay-service/**`, `payment-handler-contract`, `internal-api-contract` | `copenhagen/pay-service:<commit-sha>` |
| Merchant Mock | `backend/mocks/ucp-merchant-mock/**`, 관련 계약 | `copenhagen/ucp-merchant-mock:<commit-sha>` |
| PG Mock | `backend/mocks/pg-mock/**`, 관련 계약 | `copenhagen/pg-mock:<commit-sha>` |

정확한 변경 경로: 각 `*.gitlab-ci.yml`의 `rules:changes`. Gradle 공통 설정, `test-support` 변경 시 관련 서비스 job 실행.

## GitLab 설정

설정 권한: Maintainer 또는 Owner.

| 위치 | 설정 |
| --- | --- |
| `Settings > Merge requests > Merge checks` | `Pipelines must succeed` 활성화 |
| `Settings > Repository > Branch rules` | `develop` protected branch 지정 |
| `Allowed to merge` | 팀 정책에 따른 역할 |
| `Allowed to push and merge` | `No one` 명시 |
| 선택 | Approval rule, CODEOWNERS approval |

설정 검증: lint 실패 MR에서 merge 버튼 차단 확인.

## 배포 변수

등록 위치: `Settings > CI/CD > Variables`

| 변수 | 타입 | 값 |
| --- | --- | --- |
| `DEV_DEPLOY_HOST` | Variable | 개발 EC2 public DNS 또는 IP |
| `DEV_DEPLOY_USER` | Variable | SSH 사용자 |
| `DEV_DEPLOY_PATH` | Variable | EC2 배포 디렉터리 절대 경로 |
| `DEV_SSH_PRIVATE_KEY` | File | 배포 사용자 SSH private key, 마지막 줄바꿈 포함 |
| `DEV_SSH_KNOWN_HOSTS` | File | 신뢰 환경에서 fingerprint를 확인한 SSH host key |
| `DEV_DEPLOY_WAIT_TIMEOUT` | Variable, 선택 | health 대기 시간, 기본 120초 |

- 보호 설정: protected variable 권장, `develop` protected branch 필요
- 환경 범위: `development`. 빌드/전송 job은 `prepare` 사용
- 비밀값: 레포와 Job 로그 기록 금지. SSH 키의 masked 설정 지원 여부 확인
- Registry 변수, Deploy Token 불필요

## 실행 환경

- Runner: Gradle 실행, privileged Docker-in-Docker, EC2 SSH 포트 접근
- Docker daemon 연결 오류: Runner 관리자에게 Docker-in-Docker 허용 여부 확인
- EC2: Bash, gunzip, SSH 사용자의 sudo 없는 Docker 실행 권한

## EC2 배포 과정

전송 파일: `docker-compose.yml`, `infra/nginx/nginx.conf`, `infra/nginx/nginx.https.conf`, `infra/docker/https.override.yml`, `scripts/deploy-dev-service.sh`, `scripts/deploy-dev-nginx.sh`

1. 변경 서비스 이미지 빌드, commit SHA 태그 지정
2. SSH 압축 스트림으로 EC2 적재. 양쪽 `pipefail` 적용
3. 빌드/전송 성공 후 HTTPS 설정·인증서 사전 검사, nginx만 기동/재생성과 reload, 적재 이미지 확인
4. 임시 `.env`에 대상 이미지 기록
5. HTTPS override를 포함하여 `--no-deps --no-build --pull never --wait`로 대상 컨테이너 교체
6. health 확인, 실행 중인 Nginx 검사와 reload, `.env` 확정
7. 실패 시 진단 출력, 이전 이미지 복구와 Nginx reload 시도

동시 배포 제한: 모든 개발 배포 job이 `development-ec2` resource group 공유. DB와 Nginx 설정 파일 원복 제외.

`nginx-deploy-dev`는 nginx·HTTPS override·nginx 배포 스크립트 변경도 서버에 반영합니다. 서비스 이미지 변경이 없는 경우에도 실행되며 `deploy-smoke`와 `nginx-config-test` 성공이 필수입니다. 애플리케이션 배포 job도 같은 설정 전송·nginx 적용 절차를 먼저 실행하므로 다음 서비스 배포에서 HTTPS가 사라지지 않습니다.

`nginx-config-test`는 임시 인증서와 합성 백엔드로 실제 nginx의 HTTP/TLS 프록시, HTTPS 전환, ACME 경로, 내부 경로 차단, 백엔드 DNS 중단 중 reload를 검증합니다. 기존 전체 스택 스모크는 인증서 없는 로컬 HTTP 구성을 유지합니다. HTTPS override·nginx 배포 스크립트 변경도 전체 스모크와 그에 필요한 모든 서비스 JAR build를 선택합니다.

개발 서버에는 [nginx 문서](../nginx/README.md)의 인증서와 ACME 디렉터리가 미리 준비되어 있어야 합니다. `.env`의 `NGINX_PORT=80`, 인증서·자동 갱신 타이머는 서버에서 관리합니다. CI는 개인키를 전달하거나 갱신 타이머·방화벽을 변경하지 않습니다.

### 배포 실패 진단

- 전달 경로: 기존 SSH stdout/stderr
- 실패 단계: `startup-health`, `nginx-status`, `nginx-check`, `nginx-reload`
- 롤백 전 출력: 서비스, 신규 이미지, 실패 단계와 코드, 대상 서비스와 Nginx 상태, ExitCode, OOMKilled, health, 최근 로그
- 로그 제한: 컨테이너별 최근 100줄, 진단 줄당 4096자
- 롤백 실패 시 추가 진단 수집
- 진단 수집 오류: 롤백 계속 진행
- 종료 코드: 롤백 결과와 관계없이 최초 배포 실패 코드 유지

| 롤백 결과 | 의미 |
| --- | --- |
| `ROLLBACK SUCCESS` | 이전 이미지 복구 성공 |
| `ROLLBACK FAILED` | 복구 실패. 단계와 종료 코드 표시 |
| `ROLLBACK UNAVAILABLE` | 이전 이미지 기록 또는 로컬 이미지 없음 |

```text
FAIL deploy service=pay-service image=example/pay:commit-sha step=startup-health exit=1
ROLLBACK attempting service=pay-service
ROLLBACK SUCCESS service=pay-service
```

마스킹 대상: `.env`의 `KEY=VALUE`와 실행 환경 중 이름에 `PASSWORD`, `SECRET`, `TOKEN`, `API_KEY`, `PRIVATE_KEY`, `CREDENTIAL`이 포함된 비어 있지 않은 값. 문자 그대로 치환 후 길이 제한. 앱에서 생성한 별도 민감정보는 필터 범위 제외. 앱 로그의 비밀값과 실제 결제 데이터 기록 금지.

## 최초 준비

1. [Docker와 개발 EC2 구성](../docker/README.md)에 따라 Docker, 배포 디렉터리 준비
2. `.env`, PostgreSQL 3개, Redis 준비
3. GitLab 필수 변수 5개와 선택 변수 1개 등록
4. EC2 Security Group에서 Runner SSH 접근 허용
5. MR pipeline의 필수 job 성공 확인
6. `develop` merge 후 SSH 이미지 적재와 배포 job 성공 확인
7. `docker compose ps`로 서비스 healthy 확인
8. HTTPS 인증서 준비 후 `sh scripts/deploy-dev-nginx.sh`로 Nginx 시작

## 참고

- [Pipeline 성공 필수 설정](https://docs.gitlab.com/user/project/merge_requests/auto_merge/#require-a-successful-pipeline-for-merge)
- [Protected branch와 MR](https://docs.gitlab.com/user/project/repository/branches/protected/#require-merge-requests)
- [CI/CD SSH 키](https://docs.gitlab.com/ci/jobs/ssh_keys/)
- [Job artifact](https://docs.gitlab.com/ci/jobs/job_artifacts/)
