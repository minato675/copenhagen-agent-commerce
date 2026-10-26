# 최근 구매내역 MCP 연동 (Story 495)

## 범위

`core-integration` 프로필에 `get_purchase_history`를 추가한다. 기존 Core의
`GET /internal/v1/purchase-history`를 호출하며 Core / Pay / 앱과 DB 스키마는 변경하지 않는다.
사용자 ID를 도구 인자로 받지 않고 현재 Agent JWT를 Core에 위임한다.
필요 권한은 Core와 동일한 `purchase:handoff`이며 조회 자체는 읽기 전용이다.

## 요청과 응답

입력은 `pageSize`(생략 시 Core 기본 20, 정수 1~50)와 `cursor`(선택, 최대 4096자)뿐이다.
다음 페이지에서는 반환된 `nextCursor`를 해석하거나 수정하지 않고 그대로 전달한다.

응답의 `kind`는 `purchase.history`, `data`는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `periodStart` | Core가 계산한 최근 6개월 조회 시작 시각, ISO Instant |
| `items` | 판매처·상품별 내역, 최대 50개 |
| `nextCursor` | 다음 페이지 커서, 마지막 페이지에서는 키를 유지하고 null |

각 항목은 `merchantId`, `merchantName`, `merchantProductId`, `merchantVariantId`,
`productName`, `optionLabel`, `purchaseCount`, `totalQuantity`, `lastUnitPriceAmount`,
`currency`, `lastPurchasedAt`만 노출한다. 추가 내부 필드는 제외한다.
상품·옵션 ID는 Core 계약에 따라 null일 수 있으며 해당 항목을 삭제하지 않는다.
옵션이 없으면 `optionLabel`은 빈 문자열이다. 현재 통화 계약은 KRW다.
필수 필드 누락이나 잘못된 날짜·ID·수량·금액은 `CORE_INVALID_RESPONSE`로 처리한다.

Core는 결제 완료 기록을 조회하고 취소·실패 주문을 제외한다. MCP가 결제 여부를 재계산하지 않는다.
`items=[]`는 정상 조회 결과이며 Core 오류와 구분한다. 잘못된 커서는 `INVALID_ARGUMENT`,
접근 거절은 `ACCESS_DENIED`, 알 수 없는 Core 실패는 `CORE_SERVICE_ERROR` 등 기존 오류 계약을 따른다.
서버 오류 본문을 그대로 공개하지 않는다.

## 채팅과 재구매

도구는 데이터만 반환하며 `openai/outputTemplate` 또는 UI 리소스를 연결하지 않는다.
사용자 안내는 [채팅 중심 쇼핑 사용법](mcp-chat-shopping-481.md)을 따른다.
과거 단가·옵션을 현재 판매 조건으로 사용하지 않는다. 상품 ID가 있으면 현재 상세를 조회하고,
ID가 없거나 상품이 없어졌으면 상품명으로 검색한다. 옵션이 불명확하면 사용자에게 선택받는다.
실제 담기·구매 요청은 기존 사용자 선택·위임 및 결제 승인 흐름을 그대로 사용한다.

## 검증과 배포 체크리스트

로컬 검증 명령 (Java 21):

```powershell
./backend/gradlew.bat -p backend :apps:mcp-server:test :apps:mcp-server:checkstyleMain :apps:mcp-server:checkstyleTest
```

로컬 테스트는 입력 검증, 필드 허용 목록, nullable ID, 커서 전달 및 마지막 페이지,
권한 부족 시 Core 미호출, 위임 헤더, Core 오류와 정상 빈 내역 구분을 포함한다.
통합 테스트의 Core는 HTTP 테스트 서버다. 실제 운영 DB 및 ChatGPT 동작 검증과 구분한다.

2026-09-22 로컬 검증 결과: MCP 테스트 260개 통과(실패·건너뜀 0),
백엔드 전체 `checkstyleMain checkstyleTest build` 성공. 운영 배포와 실제 ChatGPT 검증은 미실시다.

배포 후 아래 항목을 확인한다. 로컬 테스트만으로 완료 처리하지 않는다.

- [ ] Core 이미지에 구매내역 API(490)가 포함되어 있는지 확인한다.
- [ ] 이 MR의 MCP 이미지가 실제 컨테이너에 적용되고 `core-integration`으로 실행되는지 확인한다.
- [ ] 기존 플러그인 도구 목록을 새로고침하여 `get_purchase_history`가 노출되는지 확인한다.
- [ ] 동의한 `purchase:handoff` 범위를 가진 계정으로 최근 구매내역을 조회한다.
- [ ] 서로 다른 테스트 계정에서 본인 내역만 나오고 결제 미완료 기록이 없는지 확인한다.
- [ ] 내역 없는 계정, 다음 페이지, 상품 ID가 null인 기록을 확인한다.
- [ ] 재구매 요청 시 현재 상품·옵션을 확인하고 선택이 필요한 경우 채팅으로 묻는지 확인한다.
- [ ] 구매내역 조회만으로 장바구니 변경이나 결제 요청이 생기지 않는지 확인한다.

새 환경변수, DB 데이터 수정, 새 OAuth scope는 필요하지 않다. 기존 계정에 필요한 scope가
없다면 정상 동의 흐름으로 권한을 부여한다. 토큰·개인키를 로그나 검증 문서에 남기지 않는다.
