# ADR-0008: Payment Credential은 Pay가 소유하고 Core는 영속 저장하지 않음

- **Status:** Proposed — 논의 필요
- **Draft period:** 2026-09-07 ~ 2026-09-08
- **Recorded:** 2026-09-08
- **Last updated:** 2026-09-09
- **Note:** 설계 과정에서 정리한 초안이다. 팀 논의 전이므로 확정된 결정이 아니다.

## Context

사용자 승인 후 Pay가 발급한 Payment Credential을 Core가 Merchant에 전달해야 하므로, Core가 해당 Credential을 저장해야 하는지 검토했다.

Credential은 실제 Payment 실행 권한과 연결되므로 Core에 장기 저장하면 민감한 결제 권한의 보관 범위가 넓어진다.

## Decision

Payment Credential의 원본과 생명주기는 Pay가 소유한다.

- Pay가 Credential을 발급한다.
- Core는 승인 결과와 결제 권한 참조값을 Merchant 전달에 사용한다.
- Core DB에는 Credential 원문을 영속 저장하지 않는다.
- 로그에도 Credential 원문을 남기지 않는다.
- Core가 관리해야 하는 경우 `approvalId`, `credentialId`, `status`, `expiresAt` 등 참조/상태 정보만 저장한다.
- Merchant/Pay는 Credential 사용 시 승인 대상 binding과 만료/사용 여부를 검증한다.

카드번호, CVC, PIN, 생체정보 등 결제 민감 원문 역시 Core/Agent/MCP가 보유하지 않는다.

## Alternatives Considered

### A. Core DB에 Credential 원문 저장
재전송은 쉬워지지만 결제 권한 보관 범위와 보안 책임이 커진다.

### B. Agent가 Credential을 보유
결제 권한이 비신뢰 추론 영역까지 노출되므로 채택하지 않는다.

## Consequences

### Positive
- Core의 민감 결제정보 보관 범위를 최소화한다.
- 결제 권한의 Source of Truth를 Pay로 일원화한다.
- Agent/MCP까지 Credential이 확산되는 것을 방지한다.

### Negative
- 장애 복구 시 Core가 Credential 원문 DB를 이용한 단순 재전송을 할 수 없다.
- 전달 실패 처리에는 Pay의 Credential 상태 조회/재발급 정책이 필요하다.

## Open Detail

`Core → Merchant` 전달 실패 시 기존 Credential을 다시 조회할지, 새로운 Credential을 재발급할지는 Pay API 정책으로 추가 확정이 필요하다. 현재 ADR은 **Core가 Credential 원문을 영속 저장하지 않는다**는 범위까지 확정한다.

**미결정 상태이며 팀이 결정해야 한다.** 관련 제안이 ADR-0007 Required Follow-up 3항에 있다 — "사용자가 재승인하면 Pay가 새 `credentialId`를 발급하고, Core는 새 Credential을 Merchant Complete 요청에 전달한다." 이 제안이 확정되면 본 Open Detail을 닫고 Decision으로 옮긴다.

다만 이 제안은 **재승인 경로**만 다루므로, 재승인 없이 전달만 실패한 경우(기존 Credential 재조회 가능 여부)는 별도로 정해야 한다.

## Related Decisions
- ADR-0003
- ADR-0007
- ADR-0009

## Revision History

| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | Open Detail에 ADR-0007 Required Follow-up 3항 교차 참조 및 미결정 범위 명시. **결정 내용은 변경 없음.** |
| 2026-09-09 | Status를 `Accepted`에서 `Proposed — 논의 필요`로 변경. 팀 논의 전 초안임을 명시. |
