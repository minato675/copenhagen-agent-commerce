# 구매 인계·결과 조회 소비자 계약 초안 (370)

2026-09-17. **MCP 내부 정규화 초안이며 Core/Mobile 담당자와 합의된 HTTP 계약이 아니다.**
Story312의 326은 Mock 인계, Story313의 329·330은 Mock 조회이다. 실제 연결은371·372,
종단 QA는331에서 검증한다. `/fixture/*`, `qa:*` 권한과 QA 링크를 운영 계약으로 사용하지 않는다.

## 책임 및 교체 지점

| 경계 | 제공자 책임 | MCP 책임 |
| --- | --- | --- |
| 구매 인계 | Core 장바구니·스냅샷·세션 저장, 소유권/버전/만료/멱등성 | `CorePurchaseHandoff` 소비자 포트·정규화 검증·Tool |
| 앱 이동 | Mobile App Link 수신, 로그인 후 사용자 확인·동일 구매 복원 | 허용된 링크를 명시적 버튼으로 열고 대기/다음 행동 표시 |
| 선택/검증/승인 | Mobile 배송지·카드·지문/PIN, Core/Merchant checkout, Pay 승인 | 결과 표시; 승인 API·PG 실행 Tool 없음 |
| 결과 조회 | Core가 Pay/Merchant 상태를 권한 검사 후 제공 | 승인·결제·주문·배송을 구분한 읽기 전용 조회 |

실제 전환 시 담당자가 공유 HTTP DTO/오류/인증 계약을 확정하고 Core 제공자 테스트와 MCP 소비자
테스트를 함께 실행한다. MCP 서비스·Tool·UI는 유지하고 포트 어댑터를 교체한다. 인증 계약은366,
상품 API는367과 함께 맞춘다. 다른 서비스 DB/Entity를 MCP로 가져오지 않는다.

## 인계 요청 및 응답

Tool `prepare_purchase_handoff` 입력은 `cartId`, `expectedVersion`, `idempotencyKey`뿐이다.
가격·사용자 ID·링크·callback URL·카드·배송지·승인 참조를 모델 입력으로 받지 않는다.
단일 판매처 한 건부터 시작한다. 여러 판매처는 명시적으로 거절하고 자동 분할/부분 결제하지 않는다.

```json
{"cartId":"cart-example","expectedVersion":2,"idempotencyKey":"example-request-0001"}
```

Core 어댑터 응답에는 검증된 `publicUserRef`, `connectionRef`, `data`를 넣는다. 이 두 문맥 값은
기존 인증된 신원과 대조한 뒤 Tool 결과에서 제거한다. 원본 Bearer를 무조건 Core에 전달하지 않는다.
`data`에는 아래 필드를 사용한다. 날짜는 UTC ISO-8601, 버전은 JS 안전 정수이다.

| 필드 | 의미 |
| --- | --- |
| kind / mode | `purchase.handoff` / `QA` 또는 `LIVE` |
| request | 입력 세 필드 그대로 echo; 다른 요청 응답 혼입 차단 |
| status | `READY`, `REJECTED`, `CONFLICT`, `UNKNOWN`, `EXPIRED` |
| reason | `NONE`, `EMPTY_CART`, `MULTIPLE_MERCHANTS`, `UNAVAILABLE`, `VERSION_CONFLICT`, `KEY_CONFLICT`, `OUTCOME_UNKNOWN`, `EXPIRED` |
| purchaseRef / expiresAt / appLink | READY의 공개 참조·만료·허용 링크. 나머지 상태는 null |
| nextAction | READY=`OPEN_APP`, CONFLICT/REJECTED/EXPIRED=`REVIEW_CART`, UNKNOWN=`RETRY_SAME_REQUEST` |
| checkedAt | 서버 확인 시각 |

READY는 인계 준비일 뿐 승인/결제 완료가 아니다. Core가 소유한 스냅샷은 최종 checkout 금액이 아니며
앱에서 최신 가격·재고·배송을 다시 검증해야 한다. 장바구니가 변경되면 과거 인계 재사용을 차단한다.
같은 사용자·연결·키·본문 재전송은 같은 참조/만료를 반환한다. 같은 키/다른 본문은 KEY_CONFLICT이다.
만료/거절/충돌 후 새 시도에는 사용자 확인을 요구한다. 응답 유실/UNKNOWN에는 기존 요청을 그대로
재전송하며 새 키로 조용히 재생성하지 않는다. Core는 응답 이전에 요청 결과와 스냅샷을 원자 저장한다.

## 링크와 앱 복원

서버 설정에 등록된 HTTPS origin과 고정 경로만 허용한다. 쿼리·사용자정보·포트 변경·경로 우회·임의
리다이렉트는 거절한다. 링크는 공개 구매 참조만 전달하고 참조 자체를 인증 수단으로 사용하지 않는다.
앱은 로그인 후 Core에 소유권·만료·스냅샷을 확인한다. 인증/로그인 토큰은 URL이나 widgetState에 넣지 않는다.
링크 열기 실패는 인계 재생성이 아니라 같은 링크 다시 열기로 처리한다. 브라우저에서 앱 설치 여부를
추측하지 않는다. 미설치 안내와 로그인 복귀·취소는 담당 앱의 실계약 및371 실기기 QA에서 확인한다.

QA4는 설치된 실제 앱이 없으므로 고정된 안전한 공개 문서 링크를 **QA 링크**로 표시해 호스트 외부
링크 동작만 검증한다. 로컬에서만 별도 Mock 시나리오를 사용해 로그인 필요·미설치·취소·만료 안내를
검증하며, 문서가 열린 것을 앱 구매 복원 완료로 기록하지 않는다. 운영 App Link는 미확정이다.

OpenAI `openExternal`과 지원 호스트의 MCP Apps `openLink`를 사용한다. ChatGPT 리다이렉트 허용 origin은
`openai/widgetCSP.redirect_domains`로 선언한다. 임의 `window.open`을 모바일 성공 증거로 삼지 않는다.
[공식 UI 참조](https://developers.openai.com/plugins/reference).

## QA5 결과 조회 초안

최종 Tool/API 이름은329·330에서 확정한다. 구매 공개 참조를 기본 조회 키로 사용한다.

| 결과 | 제안 필드/상태 | 검증 |
| --- | --- | --- |
| 승인 | approvalRef, approvalStatus, expiresAt | 대기/승인/거절/만료/철회 구분; 승인≠결제 성공 |
| 결제 | paymentRef?, paymentStatus?, amountMinor?, currency?, checkedAt, nextAction | 미생성=null, 진행/성공/실패/UNKNOWN 보존 |
| 영수증 | receiptRef?, receiptUrl?, maskedMethod? | 확정 결과만, 허용 HTTPS URL, 카드 원문 제외 |
| 주문 | purchaseRef, orderRef?, orderStatus, items | 결제 성공/주문 미확정 구분; 번호 임의 생성 금지 |
| 배송 | shipmentStatus, expectedDeliveryAt? | 정보 없음/불필요/진행/완료 구분; 날짜 추측 금지 |
| 목록 | items, nextCursor?, partial, failedRefs | 불투명 cursor, 응답 한도·부분 실패 보존 |

조회는 승인·청구·주문을 생성하지 않는다. UNKNOWN의 대사·상태 전이는 Pay/Core가 수행하고 MCP는 같은
참조로 다시 조회한다. 새 대화에서도 서버 소유 구매 참조를 재조회하며 widgetState를 원장으로 사용하지
않는다. 주소·카드 원문을 모델에 반환하지 않는다. 화면은 KRW를 원으로 표시하되 계약에는 통화를 보존한다.

## 제공자/소비자 검증과 미합의 목록

- Core 확인 필요: 인계 및 조회 URL/메서드, 공개 ID 매핑, scope·서비스 인증, 연결 해제, 소유권 오류,
  상태 enum, 멱등성 보존 기간, 만료·장바구니 변경 정책, pagination/응답 제한, checkout 후 서버 실행 주체.
- Mobile 확인 필요: 앱 소유 HTTPS 도메인·경로, Android association, 설치 유도, 로그인 pending intent 복원,
  앱 종료/복귀, 취소·링크 재열기. 실제 deep link와 복귀 URL은 아직 정하지 않았다.
- Pay/Merchant 재사용: 승인 생성·조회196, 인증297, checkout 검증298, credential198, 주문180,
  webhook181·184, complete176·177, 청구179·307. 완료 이력이 실제 MCP 통합 완료를 의미하지 않는다.
- 검증 행렬: 정상/빈 장바구니/다중 판매처/버전 충돌/같은 키 반복·다른 본문/응답 유실/재시작/
  만료/타 사용자·연결/변조 URL/앱 열기 거부/로그인 필요/미설치/취소/승인과 결제 상태 혼동 방지.
- Mock·Core 계약·ChatGPT 웹·S25 실제 앱 각각의 검증 수준을 따로 기록한다. 370 완료는 이 초안의
  문서·예시 검토 완료이며 담당자 합의 또는 실제 주문·결제 연결 완료가 아니다.
