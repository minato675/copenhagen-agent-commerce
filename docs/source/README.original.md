# Copenhagen

Copenhagen 프로젝트의 모노레포입니다. MCP UI에서 상품을 탐색·비교하고 구매를 시작하면,
Core가 주문을 검증하고 Pay가 모바일 앱에 최종 결제 승인을 요청합니다.
모바일 앱은 별도 프로젝트에서 관리하며, 이 저장소는 앱 연동에 필요한 API를 제공합니다.
일반 웹 프론트엔드는 이 구매 흐름의 구성요소가 아니므로 빈 frontend 디렉터리는 연결 누락이 아닙니다.
AI FDS는 별도 후속 범위입니다. [구매 흐름과 설정](docs/api/mcp-mobile-purchase-flow.md)을 확인하세요.

처음 참여한 팀원이라면 이 문서로 프로젝트를 실행한 뒤 [팀 개발 가이드](CONTRIBUTING.md)를 읽어주세요.

프로젝트 이름은 다음 표기를 기준으로 사용합니다.

| 용도 | 표기 |
| --- | --- |
| 문서와 화면 표시명 | `Copenhagen` |
| Docker, Gradle 등 기계 식별자 | `copenhagen` |
| Java 기본 package | `com.copenhagen` |

## 기술 구성

- Java 21 LTS와 Gradle Toolchain
- Spring Boot 4.1.1
- Gradle 9.3.1 멀티 프로젝트와 Wrapper
- PostgreSQL, Redis
- Docker Compose, Nginx
- GitLab CI/CD와 개발 EC2 자동 배포

## 서비스 구조

```text
MCP Server ──▶ Core Service ──▶ Pay Service ──▶ PG Mock
                    │
                    └────────▶ Merchant Mock ──▶ Pay Service
```

| 모듈 | 책임 | 소유 DB | 호출 가능 대상 |
| --- | --- | --- | --- |
| `mcp-server` | Agent 요청을 Core 계약으로 변환 | 없음 | Core |
| `core-service` | UCP 플랫폼과 구매 흐름 조정 | `core-db` | Pay, Merchant |
| `pay-service` | 권한, mandate, credential, 결제 처리 | `pay-db` | PG |
| `ucp-merchant-mock` | 외부 UCP Business 시뮬레이션 | `merchant-db` | Pay |
| `pg-mock` | 외부 결제 게이트웨이 시뮬레이션 | 없음 | 없음 |

서비스 내부 통신은 Nginx가 아니라 Docker DNS를 사용합니다. 예를 들어 MCP는 `http://core-service:8080`으로 Core를 호출합니다.

## 3분 안에 실행하기

Docker Engine과 Docker Compose v2가 설치되어 있어야 합니다.

```bash
docker compose up --build
```

상태를 확인합니다.

```bash
docker compose ps
```

정상적으로 시작되면 Core, MCP, Pay를 포함한 애플리케이션 컨테이너가 `healthy` 상태가 됩니다. 외부 요청은 기본적으로 `http://localhost:8080`의 Nginx를 통해 들어옵니다.

로그 확인과 종료:

```bash
docker compose logs -f core-service
docker compose down
```

`docker compose down --volumes`는 로컬 DB 데이터까지 삭제하므로 데이터 초기화가 필요할 때만 사용하세요.

## Backend 빌드하기

Gradle을 별도로 설치하지 말고 반드시 레포에 포함된 Wrapper를 사용합니다.

```bash
cd backend
./gradlew build
```

서비스 하나만 검사할 수도 있습니다.

```bash
./gradlew :apps:core-service:build
./gradlew :apps:mcp-server:build
./gradlew :apps:pay-service:build
```

Lint만 실행하려면:

```bash
./gradlew checkstyleMain checkstyleTest
```

## 디렉터리 안내

| 경로 | 용도 |
| --- | --- |
| `backend/apps/` | 독립 배포하는 Core, MCP, Pay 애플리케이션 |
| `backend/mocks/` | Merchant와 PG 외부 시스템 시뮬레이터 |
| `backend/libs/` | 계약, DTO, schema, validation, 테스트 지원만 공유 |
| `frontend/` | 프론트엔드 팀의 독립 작업 공간 |
| `mobile/` | 모바일 팀의 독립 작업 공간 |
| `ai/` | AI 팀의 독립 작업 공간 |
| `infra/` | Docker, Nginx, GitLab CI/CD, 모니터링 기반 |
| `docs/` | 아키텍처, API, MCP, UCP, 결제 관련 문서 |
| `scripts/` | 개발 서버 bootstrap과 배포 스크립트 |

## 현재 제공하는 API 범위

각 Spring Boot 애플리케이션의 Actuator health endpoint만 준비되어 있습니다.

```text
GET /actuator/health
```

공개 API 경로는 Nginx에 예약되어 있지만 Controller는 아직 없으므로 `404`가 정상입니다. `/internal`과 `/internal/**`는 외부에서 항상 차단됩니다.

## 팀 작업 전에 읽을 문서

1. [팀 개발 및 기여 가이드](CONTRIBUTING.md)
2. [문서 인덱스](docs/README.md)
3. [서비스와 데이터 소유권](docs/architecture/README.md)
4. [GitLab CI/CD와 EC2 배포](infra/gitlab/README.md)
5. [Docker와 EC2 최초 구성](infra/docker/README.md)

## CI/CD 요약

- `develop` 대상 MR: repository lint, Java Checkstyle, 변경 영향 서비스의 test와 build
- `develop` push 또는 merge: 검증 후 변경된 서비스 이미지만 commit SHA로 생성하고 압축 artifact로 저장
- EC2 배포: 해당 artifact만 SSH로 전송하고 image를 load한 뒤 해당 컨테이너만 health check와 함께 재실행
- MR pipeline에서는 Docker 이미지 생성과 EC2 배포를 실행하지 않음

CI가 성공해야 merge되도록 만드는 GitLab 프로젝트 설정은 레포 밖에서 별도로 켜야 합니다. 자세한 설정은 [GitLab CI/CD 문서](infra/gitlab/README.md)를 확인하세요.
