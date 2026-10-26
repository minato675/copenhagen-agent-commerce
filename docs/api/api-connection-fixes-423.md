# API 연결 수정 내역 — S15P21A207-423

## 우선 수정 10건

| 번호 | 연결 문제 | 수정 |
|---|---|---|
| F1 | 실제 상품·SKU ID의 콜론이 MCP 입력/출력 및 내부 경로 검사에서 거부됨 | 실제 ID 형식 허용, 경로 이동·인코딩 우회 문자는 계속 거부 |
| F2 | 다른 판매처의 같은 상품·SKU가 장바구니 한 줄로 합쳐짐 | 조회·옵션 변경·DB 유일성을 판매처까지 포함 |
| F3 | 옵션 추가금이 목록·상세·장바구니 가격에서 유실됨 | Merchant 옵션별 가격·재고를 공유 계약으로 전달하고 Core가 선택 옵션 가격 사용 |
| F4 | Merchant → Pay 인증 설정이 Compose에서 전달되지 않음 | 단일 토큰 및 판매처별 토큰 JSON 환경 변수 전달 |
| F5 | Core 주문 Webhook이 주문 상태를 반영하지 않음 | 등록된 공개키로 서명 검증, 결제 완료 복구, 주문 갱신, 중복·역순 이벤트 처리 |
| F6 | 완료 응답 유실·재기동 후 COMPLETING이 멈춤 | lease 기반 재처리와 Merchant GET으로 결제·주문 복구, 중복 저장 방지 |
| F7 | Pay 승인 생성 실패를 다시 처리하지 않음 | 요청 JSON 영속화, 같은 구매·멱등키로 재시도, 응답 유실 뒤 만료된 승인도 먼저 조회 복구 |
| F8 | 거래 생성 이전 실패의 null 거래 ID 때문에 실패 저장 불가 | FAILED 거래의 null ID 허용, 결과와 checkout 상태를 같은 트랜잭션에 기록 |
| F9 | 앱 장바구니가 MCP·Pay의 품목 상한을 초과함 | 신규 100줄 상한, 기존 초과 장바구니 조회·삭제 허용, 주문 검증 시 상한 검사 |
| F10 | Pay FCM·알림 worker 설정이 Compose에 누락됨 | FCM·worker 설정 전달 및 배포 예제 추가 |

## 함께 반영한 구매 흐름

[MCP 구매 → 모바일 승인 계약](mcp-mobile-purchase-flow.md)에 따라 기본 배송지 기반 자동 검증,
배송지 미등록 시 같은 구매 요청을 재개하는 API, 구매 인계·최종 승인 알림,
거절·만료·일부 결제·알림 실패 상태 조회와 알림 재전송을 연결했다.

모바일 앱은 별도 프로젝트에서 구현한다. 이 변경은 별도 앱이 사용할 Core·Pay API와 MCP 연동을 제공하며,
모바일 클라이언트 구현, 일반 웹 frontend와 AI FDS는 포함하지 않는다.

## 검증 범위

아래 결과는 `develop`의 `297853638da3bc986f8f78582dfb34cd7eabb252`를 반영한
`71bce5d895f5254b4412001ff3d3e85e3375766c`에서 2026-09-18에 수행한 로컬 검증이다.
MR !155 병합 후 모바일 구현을 제외하면서 백엔드·MCP·배포 설정은 그대로 유지했다.

| 검증 | 결과 |
|---|---|
| Backend Checkstyle·전체 build | 통과 |
| Core 테스트 (실제 PostgreSQL 포함) | 562개 통과 |
| MCP / Pay / PG Mock / Merchant Mock 테스트 | 각각 236 / 221 / 18 / 325개 통과 |
| Pay PostgreSQL 통합 테스트 | 46개 통과 |
| MCP UI 테스트 및 생성 번들 일치 | 51개 통과, 일치 |
| Docker Compose 구문 및 Git 공백 검사 | 통과 |

- Java Checkstyle, 전체 backend build/unit test
- 별도 테스트 PostgreSQL에서 Core API·Flyway·동시성·소유권·재개·복구 검증
- Pay postgresTest의 실제 PostgreSQL 마이그레이션·알림·결제·멱등성 검증
- Core–Pay 알림 인증·멱등 헤더 및 Merchant 완료 조회 HTTP 응답 계약
- MCP UI 상태·중복 요청·링크 검증 테스트와 생성 HTML 재빌드
- Docker Compose 설정 구문 확인

모바일 구현 제외 시 `mobile/`의 추적 파일이 변경 전 `2978536`과 완전히 같은지,
백엔드·MCP·배포 설정이 MR !155 병합 결과 `adb9c9a`와 같은지 확인했다.
별도 앱과의 실기기 FCM 알림 선택·생체 인증 통합 및 실제 PG 결제는 이 로컬 검증 범위가 아니다.
