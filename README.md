# Copenhagen — AI 에이전트 기반 쇼핑·구매 서비스

에이전트의 상품 탐색·비교와 장바구니 구성을 MCP로 연결하고, Core·Pay 서비스와 모바일 승인 흐름을 구성한 팀 프로젝트입니다.

| 항목 | 내용 |
| --- | --- |
| 프로젝트 | SSAFY 특화 프로젝트 · S15P21A207 |
| 기간·팀 | 2026.08.17–2026.10.02 · 5인 팀 |
| 팀 성과 | 2026.10.02 SSAFY 특화프로젝트 2등 |
| 개인 담당 | MCP 구현과 이상거래 탐지 설계 |
| 저장소 용도 | 팀 모노레포의 소스·검증 자료를 선별한 공개 포트폴리오 |

## 제가 맡은 역할: MCP 장바구니 도구

저는 MCP 구현과 이상거래 탐지 설계를 담당했습니다. 대표적인 구현 사례는 `add_cart_item`입니다.
Codex를 활용해 판매처·상품·옵션·수량을 Core 계약으로 전달하고,
응답의 사용자·요청·추가 항목·버전이 맞는지 확인한 뒤 결과를 반환하도록 구성했습니다.

| 근거 | 살펴볼 부분 |
| --- | --- |
| [CartToolConfiguration](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartToolConfiguration.java) | 장바구니 도구 등록과 호출 연결 |
| [CartService](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartService.java) | 입력·응답 검사와 Core 경계 |
| [CartSchemas](backend/apps/mcp-server/src/main/java/com/copenhagen/mcp/cart/CartSchemas.java) | 요청·응답 스키마 |
| [CartServiceTest](backend/apps/mcp-server/src/test/java/com/copenhagen/mcp/cart/CartServiceTest.java) | 정상·오류 응답에 대한 테스트 |
| [QA3 기록](backend/apps/mcp-server/docs/qa3.md) | Mock Core 검증, 사용자 격리·재시도·버전 충돌 확인 범위 |

제가 맡은 MCP 장바구니 도구는 당시 QA3에서 Mock Core를 기반으로 검증했습니다.
Core의 저장소 구현·가격 계산·실결제 운영은 제 MCP 담당 범위에 포함되지 않습니다.
팀 전체 흐름을 살펴볼 수 있도록 팀 공용 MCP UI와 Core·Pay·모바일 코드도 함께 담았습니다.

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
이번 게시 과정에서는 이 발췌본의 전체 배포나 실결제를 다시 검증하지 않았습니다.

[게시 범위와 원본 기준](docs/EXPORT_SCOPE.md) · [포트폴리오 설명](docs/PORTFOLIO.md) · [원본 팀 README](docs/source/README.original.md)

## 함께 정리한 실험과 도구

저는 초기 FDS 전처리 실험과 라즈베리파이 카메라 MCP를 코펜하겐의 관련 코드로 함께 정리했습니다.

| 경로 | 내용 |
| --- | --- |
| [experiments/fds-uae-preprocessing](experiments/fds-uae-preprocessing/README.md) | UAE 데이터의 시간순·누수 방지 전처리와 원격 GPU 실행 환경 점검 |
| [integrations/raspberrypi-camera-mcp](integrations/raspberrypi-camera-mcp/README.md) | Raspberry Pi USB/CSI 카메라 촬영과 ChatGPT 연결용 MCP |

각 폴더에서 별도로 설치·실행하는 실험과 도구입니다. 기존 Core·Pay·MCP 구매 API에 대한
연동 완료나 실거래 검증 성과로 확대하지 않습니다. 실행·장치 검증 범위는 각 README에 기록했습니다.
이전 `connect_gpu`와 `ssafy207`의 모든 원본 커밋을 그대로 보존했습니다.

[원본 이력과 통합 기록](docs/REPOSITORY_MERGE.md)
