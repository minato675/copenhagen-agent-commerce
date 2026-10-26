# 아키텍처 결정 기록(ADR)

여러 서비스나 팀에 영향을 주는 중요한 기술 결정을 기록하는 곳입니다.

> **현재 ADR 11건 전부 `Proposed` 상태입니다.**
> 설계 과정에서 정리한 초안이며 팀 논의를 거치지 않았습니다. 확정된 결정으로 인용하지 마세요.
> 논의에서 합의된 항목만 `Accepted`로 바꾸고, 각 문서의 개정 이력에 합의 날짜를 남깁니다.

## ADR 목록

| # | 안건 | 상태 | 논의할 것 |
| --- | --- | --- | --- |
| [0001](0001-service-responsibility-boundaries.md) | 서비스 책임 경계 | Proposed | 본문 전체 |
| [0002](0002-deterministic-policy-and-payment-authority.md) | 결정적 정책과 결제 권한 | Proposed | 본문 전체 |
| [0003](0003-human-in-the-loop-payment-approval.md) | 사용자 개입 결제 승인 | Proposed | 본문 전체 |
| [0004](0004-merchant-authoritative-checkout.md) | Merchant authoritative Checkout | Proposed | 본문 전체 |
| [0005](0005-direct-checkout-without-merchant-cart.md) | Merchant Cart 없는 직접 Checkout | Proposed | 본문 전체 + Required Follow-up |
| [0006](0006-approval-binding-with-checkout-digest.md) | Checkout Digest 승인 binding | Proposed | 본문 전체 + Required Follow-up |
| [0007](0007-merchant-initiated-payment-after-complete-checkout.md) | Complete Checkout 이후 Merchant 결제 요청 | Proposed | 본문 전체 + Required Follow-up 3건 |
| [0008](0008-payment-credential-ownership-and-storage.md) | Payment Credential 소유와 저장 | Proposed | 본문 전체 + Open Detail |
| [0009](0009-idempotency-and-payment-reconciliation.md) | 멱등성과 Payment UNKNOWN 복구 | Proposed | 본문 전체 + Required Follow-up 4건 |
| [0010](0010-ucp-ap2-reference-scope.md) | UCP/AP2 부분 적용 범위 | Proposed | 본문 전체 + Required Follow-up 3건 |
| [0011](0011-push-notification-purchase-handoff.md) | Pay 푸시 기반 앱 결제 승인 진입 | Proposed | App Link 제거와 배포 전환·실기기 검증 |
| [0012](0012-purchase-request-scoped-approval.md) | 결제 승인 단위는 구매 요청, Credential 단위는 Checkout | Accepted | 판매처 N곳 인증 1회, 기각한 대안 2건 |

### 회의 전 확인

각 문서의 `Required Follow-up` / `Open Detail` 절이 **아직 아무도 정하지 않은 항목**입니다. 본문(Decision)에 대한 합의와 별개로, 이 항목들은 결정 자체가 존재하지 않으므로 회의에서 따로 다뤄야 합니다.

특히 서로 얽혀 있어 함께 정해야 하는 것:

- **UCP 스펙 버전 pin** (0010) — 이걸 정하지 않으면 0007·0009의 요구 수준(MUST/SHOULD/MAY)이 확정되지 않습니다.
- **Complete 요청 스키마** (0007) — `checkoutHash` / `amountMinor` / UCP `instruments[]` 채택 여부가 한 묶음입니다.
- **재승인 시 Credential 처리** (0007 ↔ 0008 Open Detail) — 한쪽만 정하면 0008이 열린 채로 남습니다.

## ADR이 필요한 경우

- 서비스 책임 또는 호출 방향을 바꿀 때
- DB 소유권이나 데이터 경계를 바꿀 때
- 공유 계약의 범위를 바꿀 때
- 팀 전체가 따라야 할 새로운 기반 기술이나 규칙을 도입할 때

파일명은 `NNNN-짧은-결정-이름.md` 형식을 권장합니다. 문서에는 최소한 다음 내용을 포함합니다.

```text
제목
상태: Proposed(제안) | Accepted(승인) | Deprecated(폐기) | Superseded(대체)
배경과 문제
결정
검토한 대안
영향과 후속 작업
아직 결정하지 못한 것 (Required Follow-up)
참고 자료 (References)
개정 이력 (Revision History)
```

### 상태 전이

```text
Proposed ──팀 논의에서 합의──> Accepted
Proposed ──기각──────────────> Deprecated
Accepted ──새 ADR로 교체─────> Superseded
```

**팀 논의를 거치지 않은 문서는 `Accepted`로 두지 않습니다.** 작성자 혼자 정리한 내용은 `Proposed`입니다. `Accepted`로 올릴 때는 개정 이력에 합의 날짜를 남깁니다.

### 참고 자료(References)

**"우리가 이렇게 판단했다"로만 끝내지 않습니다.** 판단의 근거가 외부에 있다면 링크와 함께 남깁니다.

- 표준·스펙이 요구하는 항목: 스펙 문서 URL과 **버전**, 요구 수준(MUST / SHOULD / MAY)
- 업계·시중 서비스 관행: 해당 서비스의 공개 문서 URL

이 구분이 없으면 **스펙이 강제하는 항목과 우리가 선택한 항목을 나중에 구별할 수 없고**, 범위 축소 논의에서 준수 사항이 선택 사항으로 잘못 분류됩니다.

### 아직 결정하지 못한 것(Required Follow-up)

결정하지 못한 것을 비워두지 말고 명시적으로 적습니다. 무엇을 정해야 하는지, 선택지가 무엇인지, 어느 문서에 영향을 주는지를 함께 남깁니다. 확정되면 해당 항목을 Decision으로 옮기고 개정 이력에 기록합니다.

### 개정 이력(Revision History)

`Proposed` 문서는 논의 전이므로 자유롭게 수정합니다.

`Accepted`로 올라간 뒤에는 **결정 내용을 원칙적으로 바꾸지 않습니다.** 결정을 실제로 바꿔야 한다면 새 ADR을 만들고 기존 ADR의 상태를 `Superseded`로 변경합니다.

근거 보강, 참고 자료 추가, 미결정 항목 명시처럼 **결정을 바꾸지 않는 수정**은 상태와 무관하게 기존 문서에서 진행하되 다음을 남깁니다.

- 머리말에 `- **Last updated:** YYYY-MM-DD`
- 문서 끝에 개정 이력 표. 결정이 바뀌지 않았음을 명시합니다.

```text
| 날짜 | 변경 내용 |
|---|---|
| 2026-09-08 | 최초 기록 |
| 2026-09-09 | References 절 신설. 결정 내용은 변경 없음. |
```

## 원칙

ADR은 이미 내려진 결정을 몰래 정당화하는 문서가 아닙니다. 구현 전에 관계 팀과 논의하고, 결정과 구현을 가능하면 같은 MR에서 검토합니다.

계속 갱신되는 대조표(예: UCP 준수/이탈 매트릭스)는 ADR이 아니라 해당 도메인 문서 디렉터리에 둡니다. ADR은 결정 시점의 스냅샷입니다.
