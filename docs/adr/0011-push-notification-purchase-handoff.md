# ADR-0011: Pay 푸시 알림으로 앱 결제 승인 연결

- **Status:** Proposed — 팀 검토 대기
- **Recorded:** 2026-09-18
- **Context:** S15P21A207-423 후속 수정. 사용자가 재확인한 알림 기반 흐름을 반영한다.

## Context

Core의 구매 worker와 Pay 알림 처리는 구현됐지만 MCP 기동과 Core 구매 요청 생성에
HTTPS App Link가 필수로 남아 있었다. 앱 링크가 미정이면 상품 검색 프로필도 시작할 수 없고,
구매 요청은 503으로 거절되어 알림 처리까지 도달하지 못했다.

## Decision

1. MCP는 구매 요청을 생성하고, Core는 앱이 돌려준 배송지로 재고·최종 금액을 검증한다.
   배송지는 등록 여부와 관계없이 항상 사용자가 앱에서 고른다(PAY-05.1).
2. Pay가 승인 요청과 푸시 알림을 생성한다. 사용자가 알림을 선택하면 별도 앱에서 주문을
   확인하고 결제를 승인한다. 배송지 선택·등록은 같은 구매 요청을 보완해 진행한다.
3. MCP 웹은 상태 조회와 알림 재전송을 제공한다. 앱 이동 링크를 만들거나 열지 않는다.
4. `MCP_APP_LINK_BASE`, `CORE_INTERNAL_APP_LINK_BASE`를 제거한다. MCP 서비스 인증 키와
   Core 공개키 등록, 사용자·연결 소유권 및 멱등성 검증은 유지한다.

## Alternatives Considered

- HTTPS App Link를 필수로 유지: 합의한 푸시 진입 흐름에 불필요한 배포 의존성을 만든다.
- 임시 URL을 넣어 기동: 실제 앱 화면으로 연결되지 않는 설정을 배포하게 된다.

## Consequences and Migration

- Core 구매 생성 응답에서 `appLink`를 제거한다. 구매 ID·장바구니 버전·상태·만료와
  `nextAction`은 유지한다. 초기 `PENDING_ADDRESS`는 worker 처리 전 생성 스냅샷이다.
- Core와 MCP를 함께 배포한다. 구 MCP는 `appLink`를 요구하므로 전환 시 호환되지 않는다.
  새 MCP는 기존 멱등 응답에 남은 링크를 제외하고 같은 구매 ID로 상태를 확인한다.
- 새 MCP 이미지 배포 후 ChatGPT 도구 정보를 새로고침하고 새 대화에서 확인한다.
- 실기기에서는 Pay 알림 설치 등록·FCM·알림 선택 후 앱 화면 복원을 검증해야 한다.
  이 변경은 별도 앱 코드를 추가하지 않는다.

## Required Follow-up

- 서버의 MCP 개인키 읽기 권한과 Core 공개키 등록, `core-integration` 활성화 확인.
- Core 구매 worker, Pay 알림 worker·FCM 설정 및 실기기 승인 흐름 검증.

## References

- [구매 승인 흐름과 별도 앱 API](../api/mcp-mobile-purchase-flow.md)
- [MCP 배포 절차](../../infra/docker/mcp-oauth-deployment.md)

## Revision History

| 날짜 | 변경 내용 |
| --- | --- |
| 2026-09-18 | 사용자 확인 흐름과 App Link 의존성 제거·배포 전환 기록 |
| 2026-09-21 | 기본 배송지 자동 선택을 제거하고 배송지 선택을 항상 앱에서 받도록 정정(PAY-05.1, 이슈 479) |
