# Copenhagen — AI 에이전트 기반 쇼핑·구매 서비스

에이전트의 상품 탐색·비교와 장바구니 구성을 MCP로 연결하고, Core·Pay 서비스와 모바일 승인 흐름을 구성한 팀 프로젝트입니다.

| 항목 | 내용 |
| --- | --- |
| 프로젝트 | SSAFY 특화 프로젝트 · S15P21A207 |
| 기간·팀 | 2026.08.17–2026.10.02 · 5인 팀 |
| 팀 성과 | 2026.10.02 SSAFY 특화프로젝트 2등 |
| 개인 담당 | MCP 구현과 이상거래 탐지 설계 |
| 저장소 용도 | 팀 모노레포의 소스·검증 자료를 선별한 비공개 포트폴리오 |

## 개인 기여: MCP 장바구니 도구

김지호는 MCP 구현과 이상거래 탐지 설계를 담당했습니다. 대표 사례는 `add_cart_item`입니다.
Codex를 활용해 판매처·상품·옵션·수량을 Core 계약으로 전달하고,
응답의 사용자·요청·추가 항목·버전이 맞는지 확인한 뒤 결과를 반환하도록 구성했습니다.

| 근거 | 살펴볼 부분 |
| --- | --- |
| [CartToolConfiguration](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartToolConfiguration.java) | 장바구니 도구 등록과 호출 연결 |
| [CartService](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartService.java) | 입력·응답 검사와 Core 경계 |
| [CartSchemas](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartSchemas.java) | 요청·응답 스키마 |
| [CartServiceTest](backend/apps/mcp-server/src/test/java/com/copenhagen/mcp/cart/CartServiceTest.java) | 정상·오류 응답에 대한 테스트 |
| [QA3 기록](backend/apps/mcp-server/docs/qa3.md) | Mock Core 검증, 사용자 격리·재시도·버전 충돌 확인 범위 |

개인 담당의 근거로 사용한 QA3는 Mock Core 검증을 중심으로 한 당시 기록입니다.
Core의 저장소 구현·가격 계산·실결제 운영을 개인 MCP 성과로 확대하지 않습니다.
팀 공용 MCP UI와 Core·Pay·모바일 코드의 포함 여부가 개인 구현 범위를 의미하지 않습니다.

## 팀 서비스 구조

```text
에이전트 / MCP UI ─ MCP Server ─ Core ─ Pay ─ PG Mock
                               └─ Merchant Mock
                                     모바일 앱: 승인·상태 확인
```

- MCP: 에이전트 요청·결과 변환, 인증 경계, 도구·UI 연결.
- Core: 상품·장바구니·주문 흐름 조정.
- Pay·Mock: 승인·결제 계약과 외부 시스템 시뮬레이션.
- AI: FDS 설계·평가 코드. AI 분석의 전체 실거래 운영 적용 여부와는 구분합니다.
- 모바일: Android 클라이언트 소스. 원본 루트 README에는 초기 단계 설명이 남아 있어 현재 파일 구성과 함께 읽어야 합니다.

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| [backend/apps/mcp-server](backend/apps/mcp-server/README.md) | MCP 도구·인증·UI·테스트 |
| [backend/apps](backend/apps/) · [backend/mocks](backend/mocks/) | Core·Pay·외부 시스템 Mock |
| [ai](ai/README.md) | 이상거래 탐지·의도 분석·평가 코드와 설계 문서 |
| [mobile](mobile/) | 모바일 클라이언트 |
| [docs](docs/) · [infra](infra/) | 설계·API·환경 구성·배포 자료 |

## 실행과 검증 범위

Java 21과 프로젝트 Gradle Wrapper를 사용합니다. 모듈별 명령은 해당 README를 기준으로 합니다.

```powershell
cd backend
.\gradlew.bat :apps:mcp-server:test --tests com.copenhagen.mcp.cart.CartServiceTest
```

운영 API 키·인증 설정·DB·외부 서비스는 별도 환경 설정이 필요합니다.
생성 카탈로그 일부와 별도 webcam 패키지의 실행 파일은 게시에서 제외했습니다.
이 발췌본의 전체 배포나 실결제를 검증한 것으로 해석하지 않습니다.

[게시 범위와 원본 기준](docs/EXPORT_SCOPE.md) · [포트폴리오 설명](docs/PORTFOLIO.md) · [원본 팀 README](docs/source/README.original.md)
