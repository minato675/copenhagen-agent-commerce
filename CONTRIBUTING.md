# 팀 개발 및 기여 가이드

이 문서는 새 팀원이 작업 위치를 결정하고, 로컬 검증을 거쳐, 안전하게 Merge Request를 올리는 전체 과정을 설명합니다.

## 처음 온 팀원의 첫 작업 순서

1. Java 21, Docker Engine, Docker Compose v2를 준비합니다. Gradle은 따로 설치하지 않습니다.
2. `develop`을 최신 상태로 맞추고 작업 branch를 만든 뒤, 곧바로 `develop` 대상 Draft MR을 생성합니다.
3. 레포 루트에서 `docker compose up --build`로 현재 상태가 정상인지 확인합니다.
4. 아래 표에서 책임 모듈을 고르고 해당 모듈의 README를 읽습니다.
5. 코드와 같은 모듈에 테스트를 작성합니다. 계약이나 DB가 바뀌면 관련 문서와 migration도 함께 수정합니다.
6. `backend/`에서 `./gradlew checkstyleMain checkstyleTest build`를 실행합니다.
7. 작업이 끝나면 Draft를 해제해 정식 MR로 전환하고, pipeline과 review가 모두 통과한 뒤 merge합니다.

문제가 생겼을 때는 무작정 다른 서비스 코드를 가져오지 말고 먼저 서비스 책임, API 계약, DB 소유권을 확인합니다.

## 1. 작업을 시작하기 전에

먼저 다음 세 가지를 확인합니다.

1. 변경하려는 기능의 책임 서비스가 어디인지 확인합니다.
2. 다른 서비스와 주고받는 데이터 구조가 바뀌는지 확인합니다.
3. DB 변경이 있다면 어느 서비스가 그 DB를 소유하는지 확인합니다.

책임 서비스가 불분명하거나 두 서비스가 같은 데이터를 직접 소유해야 하는 것처럼 보이면 코드를 작성하기 전에 팀과 논의하고 `docs/adr/`에 결정을 기록하세요.

## 2. 작업 위치 결정하기

| 하려는 작업 | 작업할 위치 |
| --- | --- |
| UCP 구매 흐름 조정 | [`backend/apps/core-service/`](backend/apps/core-service/README.md) |
| Agent-facing MCP adapter | [`backend/apps/mcp-server/`](backend/apps/mcp-server/README.md) |
| 권한, mandate, credential, 결제 처리 | [`backend/apps/pay-service/`](backend/apps/pay-service/README.md) |
| 외부 Merchant 동작 시뮬레이션 | [`backend/mocks/ucp-merchant-mock/`](backend/mocks/ucp-merchant-mock/README.md) |
| 외부 PG 동작 시뮬레이션 | [`backend/mocks/pg-mock/`](backend/mocks/pg-mock/README.md) |
| UCP 요청/응답 계약 | [`backend/libs/ucp-contract/`](backend/libs/ucp-contract/README.md) |
| 결제 handler 요청/응답 계약 | [`backend/libs/payment-handler-contract/`](backend/libs/payment-handler-contract/README.md) |
| 내부 서비스 API 계약 | [`backend/libs/internal-api-contract/`](backend/libs/internal-api-contract/README.md) |
| 여러 모듈에서 재사용하는 테스트 fixture | [`backend/libs/test-support/`](backend/libs/test-support/README.md) |

Frontend, Mobile, AI 팀은 각자의 최상위 작업 공간만 사용합니다. 해당 팀이 합의하기 전에는 다른 팀이 framework나 build tool을 먼저 추가하지 않습니다.

## 3. 반드시 지켜야 하는 모듈 경계

애플리케이션 모듈은 다른 애플리케이션 모듈을 Gradle dependency로 가져오면 안 됩니다.

```gradle
// 금지
implementation project(':apps:pay-service')
implementation project(':apps:core-service')
implementation project(':mocks:ucp-merchant-mock')
```

서비스 간 호출은 공유 계약을 기준으로 HTTP를 사용합니다. 공유 라이브러리에 넣을 수 있는 것은 다음뿐입니다.

- API 계약과 interface
- Request/Response DTO
- schema와 enum
- 입력 validation 및 계약 지원 utility
- 테스트 fixture와 테스트 지원 코드

공유하면 안 되는 것:

- JPA Entity와 Repository
- Domain Service
- Application Service 구현
- 특정 서비스의 비즈니스 규칙
- 특정 서비스에서만 사용하는 내부 구현

코드 중복을 없애기 위해 비즈니스 구현을 공유 라이브러리로 옮기지 마세요. 중복보다 서비스 간 결합이 더 큰 문제가 될 수 있습니다.

## 4. 서비스 간 계약을 변경하는 순서

서비스 사이의 요청이나 응답이 바뀐다면 다음 순서로 작업합니다.

1. 담당 계약 모듈에서 DTO 또는 schema를 변경합니다.
2. 제공하는 서비스에서 새 계약을 구현합니다.
3. 호출하는 서비스에서 새 계약을 사용합니다.
4. 제공자와 소비자 테스트를 모두 추가합니다.
5. 하위 호환이 깨진다면 API 문서와 ADR에 변경 이유 및 전환 방법을 기록합니다.

계약 모듈 선택 기준:

```text
UCP 공개 계약              → ucp-contract
결제 handler 계약          → payment-handler-contract
우리 서비스 사이 내부 계약 → internal-api-contract
```

## 5. DB 변경 규칙

DB를 소유한 서비스만 해당 DB schema를 변경합니다.

```text
core-service          → core-db
pay-service           → pay-db
ucp-merchant-mock     → merchant-db
```

Flyway migration 위치:

```text
backend/apps/core-service/src/main/resources/db/migration/
backend/apps/pay-service/src/main/resources/db/migration/
backend/mocks/ucp-merchant-mock/src/main/resources/db/migration/
```

새 migration은 이미 적용된 파일을 수정하지 말고 다음 버전 파일로 추가합니다.

```text
V2__create_orders.sql
V3__add_order_status.sql
```

Core migration에서 Pay 테이블을 만들거나, 한 서비스가 다른 서비스 DB에 직접 접속하는 것은 금지합니다.

## 6. 설정과 비밀정보

로컬 기본값이 아닌 실제 비밀번호, token, private key는 commit하지 않습니다.

- 애플리케이션 설정은 `application.yml`에 환경변수 placeholder로 정의합니다.
- 로컬 값은 Git에 포함되지 않는 `.env`를 사용합니다.
- CI 비밀정보는 GitLab CI/CD Variables를 사용합니다.
- 운영 또는 개발 서버 key를 코드와 문서 예시에 실제 값으로 넣지 않습니다.

Redis는 현재 공유 인프라로만 존재합니다. 캐시, lock, session 등 구체적인 사용 목적이 정해지기 전에는 speculative한 Redis domain logic을 추가하지 않습니다.

## 7. 브랜치에서 작업하기

항상 최신 `develop`에서 새 브랜치를 만듭니다.

```bash
git switch develop
git pull --ff-only
git switch -c feature/<issue-key>-<short-description>
```

예시는 형식만 보여주며 실제 issue key와 설명을 사용해야 합니다. Bug fix라면 `fix/`, 문서만 수정한다면 `docs/`처럼 목적이 드러나는 prefix를 사용할 수 있습니다. 팀에 이미 합의된 convention이 있다면 그 규칙을 우선합니다.

한 MR에는 하나의 목적만 담습니다. Core 기능 변경과 관계없는 Pay 정리 작업을 같은 MR에 섞지 않습니다.

## 8. 개발 중 검증하기

가장 빠른 반복은 변경한 모듈만 검사하는 것입니다.

```bash
cd backend
./gradlew :apps:core-service:test
./gradlew :apps:core-service:build
```

MR을 올리기 전에는 전체 검증을 실행합니다.

```bash
cd backend
./gradlew checkstyleMain checkstyleTest build
```

컨테이너 변경이 포함되었다면 해당 이미지를 별도로 만듭니다.

```bash
docker build \
  --file backend/apps/core-service/Dockerfile \
  --tag copenhagen/core-service:local \
  backend
```

통합 동작을 확인하려면 레포 루트에서:

```bash
docker compose up --build
docker compose ps
docker compose logs -f <service-name>
```

## 9. 테스트 작성 위치

테스트는 대상 코드와 같은 모듈의 `src/test/java`에 둡니다.

```text
src/main/java/com/example/Foo.java
src/test/java/com/example/FooTest.java
```

- 비즈니스 규칙은 빠른 unit test로 검증합니다.
- DB나 외부 HTTP가 필요한 테스트는 integration test임을 명확히 구분합니다.
- 실제 PG나 Merchant를 테스트에서 직접 호출하지 말고 Mock 서비스를 사용합니다.
- 공용 fixture가 정말 여러 모듈에서 필요할 때만 `test-support`로 옮깁니다.

## 10. Merge Request 올리기

MR은 작업 branch를 만든 직후 Draft 상태로 먼저 생성하고, 작업이 끝나면 그 MR의 Draft를 해제해 정식 MR로 전환합니다. 작업 완료 시점에 MR을 새로 만들지 않습니다. 세부 규칙은 `docs/git-convention/mr-convention.md`를 따릅니다.

MR의 target branch는 `develop`입니다. MR pipeline에서는 다음이 자동으로 실행됩니다.

```text
repository-lint
backend-lint(Checkstyle, Backend 변경 시)
변경 영향 서비스 test
변경 영향 서비스 build
deploy-smoke(Backend·배포 설정 변경 시)
```

MR에서는 임시 환경의 Docker image 빌드·기동·업데이트·복구 스모크 테스트를 실행하며, EC2 배포는 실행하지 않습니다. [배포 스모크 테스트](infra/gitlab/README.md#mr-배포-스모크-테스트)와 나머지 Pipeline이 모두 성공하고 review가 끝난 뒤 merge합니다.

MR 설명에는 최소한 다음 내용을 적습니다.

- 왜 변경했는가
- 무엇이 달라졌는가
- 어떤 모듈과 계약에 영향이 있는가
- 어떻게 검증했는가
- DB migration 또는 환경변수 변경이 있는가

## 11. Draft 해제 전 최종 체크리스트

- [ ] 올바른 서비스 또는 계약 모듈에서 작업했다.
- [ ] 다른 애플리케이션 구현 모듈을 dependency로 추가하지 않았다.
- [ ] 공유 라이브러리에 비즈니스 구현을 넣지 않았다.
- [ ] DB 변경은 소유 서비스의 새 Flyway migration으로 추가했다.
- [ ] 실제 비밀번호, token, private key를 commit하지 않았다.
- [ ] 변경한 동작의 테스트를 작성했다.
- [ ] `./gradlew checkstyleMain checkstyleTest build`가 성공한다.
- [ ] Docker 변경이 있다면 해당 image build를 확인했다.
- [ ] API 또는 아키텍처 변경을 관련 문서에 반영했다.
- [ ] MR에 관련 없는 파일 변경이 섞이지 않았다.

## 12. Merge 이후

변경이 `develop`에 들어가면 관련 서비스만 다음 순서로 배포됩니다.

```text
검증 → commit SHA Docker image 생성 → 압축 artifact 생성
     → EC2로 해당 artifact만 전송 → docker load → 해당 container만 재실행 → health check
```

배포가 실패하면 GitLab Job log와 EC2의 `docker compose logs <service-name>`을 확인합니다. CI는 이전 이미지가 `.env`에 기록되어 있으면 복구를 시도하지만, 복구 성공 여부도 반드시 확인하세요.

## 13. 자주 쓰는 명령

```bash
# 전체 Backend 검사
cd backend && ./gradlew build

# 한 서비스 테스트
cd backend && ./gradlew :apps:pay-service:test

# Lint
cd backend && ./gradlew checkstyleMain checkstyleTest

# 전체 로컬 환경 시작
docker compose up --build

# 실행 상태
docker compose ps

# 특정 서비스 로그
docker compose logs -f pay-service

# 특정 서비스만 다시 만들기
docker compose up -d --build --no-deps pay-service

# 컨테이너 종료, 데이터 보존
docker compose down
```
