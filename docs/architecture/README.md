# 아키텍처와 소유권

## 서비스 호출 흐름

```text
MCP ──▶ Core ──▶ Pay ──▶ PG Mock
          │
          └────▶ Merchant Mock ──▶ Pay
```

모든 화살표는 Docker service DNS를 이용한 직접 내부 HTTP 연결입니다. 내부 호출을 외부 Nginx로 우회하지 않습니다.

## 책임과 데이터 소유권

| 실행 모듈 | 책임 | 소유 DB | 허용된 outbound 대상 |
| --- | --- | --- | --- |
| `mcp-server` | Agent-facing MCP adapter | 없음 | `core-service` |
| `core-service` | UCP 플랫폼과 구매 orchestration | `core-db` | `pay-service`, `ucp-merchant-mock` |
| `ucp-merchant-mock` | 외부 UCP Business 시뮬레이터 | `merchant-db` | `pay-service` |
| `pay-service` | 결제 권한과 처리 경계 | `pay-db` | `pg-mock` |
| `pg-mock` | 외부 PG 시뮬레이터 | 없음 | 없음 |

## 컴파일 의존성과 실행 의존성

애플리케이션끼리는 Gradle implementation dependency를 맺지 않습니다. 실행 중 HTTP로 호출하고, 필요한 데이터 모양만 `backend/libs`의 계약 모듈에서 공유합니다.

```text
허용: core-service ──HTTP──▶ pay-service
금지: core-service ──Gradle dependency──▶ pay-service
```

`backend/libs`는 plain Java module이며 계약, DTO, schema, validation/support utility, test support만 포함합니다. JPA Entity, Repository, Domain Service, Application 구현은 각 애플리케이션 모듈이 소유합니다.

## DB 경계

한 서비스는 자기 DB에만 접속합니다. 다른 서비스의 테이블을 직접 조회하지 않고 API 계약을 통해 필요한 데이터를 요청합니다.

Flyway migration 역시 DB 소유 서비스 안에만 둡니다. 이미 적용된 migration 파일은 수정하지 않고 새 버전 파일을 추가합니다.

## Nginx 경계

Nginx는 외부 ingress 전용입니다. MCP, Core 공개 API, Pay 공개 API와 discovery 경로만 전달하며 `/internal`과 `/internal/**`는 차단합니다.

## 중요한 결정을 바꿀 때

호출 방향, DB 소유권, 공유 계약 범위처럼 여러 팀에 영향을 주는 변경은 구현 전에 논의하고 `docs/adr/`에 기록합니다. 자세한 작업 규칙은 [팀 개발 가이드](../../CONTRIBUTING.md)를 따릅니다.
