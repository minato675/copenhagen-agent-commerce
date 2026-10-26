# QA3 장바구니 구성과 상태 유지

## 책임 경계

Story 311의 Task 319(추가), 320(조회·견적), 321(수정·삭제)를 구현한다.
장바구니의 저장·변경·가격 및 주문 승인 대상 관리는 **Core 책임**으로 사용자와 확인했다.
실제 요청 흐름은 MCP → Core → 쇼핑몰 UCP이며 MCP가 쇼핑몰을 직접 호출하지 않는다.

`CoreCart`, `CoreCartView`, `CoreCartEdits`는 교체 가능한 MCP 내부 포트다.
현재 schema와 `/fixture/cart/{add,view,update,delete}`는 정규화 모델과 테스트 경로이며,
실제 Core HTTP endpoint·공유 DTO·서비스 인증 계약은 아직 확정되지 않았다.
운영 연동은 별도 어댑터에서 확정된 계약을 이 모델로 변환해야 한다.
Core·UCP·공용 계약·DB·CI 모듈은 변경하지 않는다.

`mcp.cart.enabled=false`가 기본값이다. 활성화에는 세 Core 포트 구현과 명시적인
`mcp.cart.read-scopes`, `mcp.cart.write-scopes`가 필요하다.
조회 권한만으로 쓰기 권한을 모두 충족하는 설정은 기동 시 차단한다.
환경 변수는 `MCP_CART_ENABLED`, `MCP_CART_READ_SCOPES`, `MCP_CART_WRITE_SCOPES`다.
QA 인증·Mock Core·로컬 미리보기·파일 저장소는 `src/test`에만 있으며 운영 JAR에서 제외된다.

## 처리 규칙

- `get_cart`는 인증된 사용자 자신의 현재 장바구니와 Core 견적을 조회한다. 사용자 ID는 입력받지 않는다.
- `add_cart_item`은 판매처·상품·실제 옵션 ID와 수량을 받는다. 가격은 Core가 결정한다.
- `update_cart_item`의 수량은 증감량이 아닌 변경 후 총수량이다. 옵션은 상세 응답에서 선택한다.
- `delete_cart_item`은 확인한 항목만 삭제한다. UI에서 삭제 확인을 거친다.
- 변경에는 조회한 `cartId`, `expectedVersion`, 요청별 `idempotencyKey`가 필요하다.
  결과가 불확실한 경우 키·본문·버전을 그대로 보존해 재시도한다. 새 키로 자동 재요청하지 않는다.
- 버전 충돌 시 최신 장바구니를 조회하고 사용자가 다시 선택한다. 성공은 `APPLIED` 응답만 인정한다.
- 사용자/연결 소유권, 요청 echo, 버전, 변경 항목, 정수 금액, 중복 항목과 견적 계산을 검증한다.
- 상품 합계·할인·배송비·최종 금액은 판매처와 통화별로 표시한다. 모르는 금액은 null/미확정이며
  0원으로 바꾸지 않는다. 통화를 합산하거나 미확정 금액을 결제 가능 금액으로 표시하지 않는다.
- 모든 변경은 `REVALIDATION_REQUIRED` 상태다. 실제 Core에서는 장바구니 변경과 함께 기존 승인
  대상의 무효화·재검증을 원자적으로 보장해야 한다. 주문·결제 및 승인 절차 구현은 QA4 이후 범위다.

UI는 검색 A → 담기 → 다른 검색 B → 담기 이후에도 같은 Core 장바구니를 조회한다.
장바구니 팝업에서 옵션·수량·삭제를 처리하며 계속하기로 검색에 돌아간다.
ChatGPT widget state에는 검색 조건과 미확정 요청 참조만 저장한다. 상품 원장이나 가격을 저장하지 않는다.

## 로컬 Mock 실행

Java 21과 Python 3을 사용한다. 저장소 루트에서 제공 카탈로그를 변환한 후 QA3용으로 복사한다.

```powershell
python backend/apps/mcp-server/scripts/import-qa-catalog.py "C:\경로\korean_products_clean_bundle.zip"
New-Item -ItemType Directory -Force backend/apps/mcp-server/build/qa3 | Out-Null
Copy-Item -LiteralPath backend/apps/mcp-server/build/qa2/catalog.json -Destination backend/apps/mcp-server/build/qa3/catalog.json
powershell -NoProfile -ExecutionPolicy Bypass -File backend/apps/mcp-server/scripts/start-qa.ps1 -Stage 3
```

미리보기: http://127.0.0.1:8093/qa · MCP: http://127.0.0.1:8093/mcp

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/apps/mcp-server/scripts/stop-qa.ps1 -Stage 3
```

한글 경로에서 Gradle worker가 실패하면 QA2와 같은 ASCII junction을 사용한다.
`C:\Users\SSAFY\.codex\build-paths\S15P21A207`은 현재 저장소를 가리키며 별도 checkout이 아니다.
QA3 서버는 loopback 전용이고 가상 신원은 4시간 후 만료된다. 재시작하면 새 만료 시간을 발급한다.
외부 사용자 인증 헤더와 실제 사용자 데이터는 받지 않는다.

원본 SQLite는 read-only로 사용한다. 1,408개 상품과 가상몰 A/B/C의 가격 변형은 QA2 fixture를 재사용한다.
모르는 재고는 확인된 값으로 바꾸지 않는다. 원본에 없는 배송비·할인은 아래의 명시적인 QA 규칙으로
견적을 만들며 실제 판매처 값으로 표시하지 않는다. `build/qa3/catalog.json`과 `cart.json`은 Git에서 제외된다.

2026-09-16 사용자 요청으로 Mock Core에 다음 원화 견적 규칙을 적용했다.

- 판매처별 상품금액의 5% 할인, 100원 미만 절사.
- 할인 후 판매처별 상품금액이 30,000원 이상이면 배송비 0원, 그 미만이면 3,000원.
- 같은 판매처의 여러 항목에는 배송비를 한 번 적용하고, 다른 판매처는 각각 계산한다.
- 모든 금액은 `ESTIMATED`다. 상품 가격 미확인·판매처 실패·정책 없는 통화는 null을 유지한다.
- UI에서 계산 기준을 펼쳐 볼 수 있다. 실제 Core의 정책은 이 테스트 코드를 복사하지 않고 어댑터로 연동한다.

`QaCartStore`는 테스트용 Core의 파일 저장소다. 요청 결과와 장바구니를 임시 파일에 함께 기록한 뒤
원자적으로 교체한다. 프로세스 잠금으로 동시 실행을 막으며 재시작 후에도 동일 요청 결과를 복원한다.
최대 100개 항목, 항목당 수량 99개, 사용자별 요청 기록 1,000개, 파일 30 MB 한도다.
한도 초과 시 기존 기록을 지워 중복 실행을 허용하지 않고 요청을 거절한다.
이 파일 저장 방식을 실제 Core의 운영 저장소로 사용하지 않는다.

## ChatGPT 연결과 갱신

공식 tunnel-client 및 사용 권한이 있는 Tunnel ID로 최초 연결한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/apps/mcp-server/scripts/start-qa-tunnel.ps1 `
    -Stage 3 -TunnelId <Tunnel-ID> -ClientPath <공식-클라이언트-경로>
```

런타임 키는 보안 콘솔에 직접 입력하며 파일이나 채팅에 저장하지 않는다.
`-Check`는 로컬 준비 확인일 뿐 원격 연결 성공을 뜻하지 않는다.
같은 Tunnel ID로 이전 QA 데몬이 실행 중이면 소유한 이전 데몬만 종료한 뒤 전환한다.

**이후 개발에서는 현재 터널 콘솔을 계속 유지한다.** 같은 주소의 서버 코드는 QA3 서버만
중지·재시작해 반영하고, 도구 schema·화면 리소스 변경은 ChatGPT 앱 설정의 새로 고침으로 반영한다.
새 대화에서 갱신한 앱을 선택한다. 키를 매번 다시 입력하거나 터널을 다시 만들 필요는 없다.
터널이 종료됐거나 연결 주소·프로필을 바꿀 때만 클라이언트를 다시 실행한다.

연결 앱 이름은 `Copenhagen QA3`이며 장바구니 추가·조회·수정·삭제 4개 도구가 추가된다.
새 Chat 대화에서 앱을 선택하고 `코펜하겐 열어줘. 미쟝센 상품을 보여줘.`로 시작한다.
화면의 LOCAL QA3 표기와 장바구니 버튼을 확인한다. 기존 QA2 카드는 갱신된 UI가 아닐 수 있다.

## 검증 기록 — 2026-09-16

- 전체 backend `checkstyleMain checkstyleTest build` 성공. MCP 테스트 189개, 실패·skip 0.
- UI Node 테스트 22개 통과. 동일 요청 재시도·widget state 복원·동시 클릭·수정/삭제 응답 검증 포함.
- Mock Core HTTP와 실제 MCP 연결, 사용자 격리, 원자적 저장, 재시작 멱등성, 버전 충돌 검증 통과.
- 로컬 Chrome에서 A 담기 → B 재검색·담기 → 두 판매처 유지 → 서버 재시작 복원 확인.
- 옵션을 컨디셔너로 바꾸고 총수량 3개로 변경해 금액 갱신, 다른 항목 삭제 확인.
- 412px·320px 화면에서 가로 넘침 없음, 팝업 내부 스크롤, 하단 두 버튼 같은 너비 확인.
- 조회 권한에 쓰기 요구 범위가 포함되는 잘못된 설정을 차단하고 권한 회귀 검사 통과.
- ChatGPT/S25 실기기 검증 결과는 아래 체크리스트와 별도로 기록한다. 로컬 검증으로 대체하지 않는다.

## S25 확인 항목

1. 미쟝센 상품의 판매처·필수 옵션·수량을 선택해 담고 성공 안내와 장바구니 수량을 확인한다.
2. 다른 상품을 검색해 담아도 앞서 담은 상품이 남는지, 다시 열기·새 대화에서도 복원되는지 확인한다.
3. 장바구니에서 수량·옵션을 변경하고 해당 항목·금액만 갱신되는지 확인한다.
4. 한 항목을 삭제하고 다른 항목이 유지되는지 확인한다.
5. 여러 항목에서 내부 스크롤·하단 버튼·긴 이름을 확인하고, 배송비·최종 금액 미확정이 0원과 구분되는지 확인한다.
6. 오류 후 재시도는 같은 요청 한 번만 적용되는지 확인한다. 서버 중단·복구는 진행자와 시간을 맞춰 수행한다.

실기기 확인 및 원격 CI 완료 전 Story/Task를 완료 처리하지 않는다. 실제 Core 계약 연동은 별도 검증이 필요하다.

## S25 피드백 반영 — 2026-09-16

사용자는 추가·조회·수정·삭제·복원에 기능 문제가 없음을 확인했다. 다만 할인·배송비·총액 미확정,
KRW 표기, 검색 화면과 장바구니의 스크롤 부담을 개선하도록 요청했다.

- Mock 규칙으로 할인·배송비·예상 총액을 계산한다. 예: 상품 26,100원 − 할인 1,300원 + 배송비 3,000원 = 27,800원.
- 원화 코드를 숨기고 `26,100원`으로 표시한다. 다른 통화의 구분과 미확인 금액 처리는 보존한다.
- 검색 필터는 접고 적용 수를 표시하며, 상품군 확인이 필요한 응답에서는 자동으로 펼친다.
- 모바일 검색 카드·헤더·여백을 줄인다. 장바구니 상품명·옵션은 짧게 표시하고 전체 내용은 변경 화면에서 확인한다.
- 장바구니 예상 총액과 하단 버튼을 목록 스크롤 영역 밖에 두며 QA 계산 기준은 접어서 표시한다.
- MCP 192개와 UI 22개 테스트, 전체 backend build·Checkstyle, 번들 일치 검사 통과.
- 기존 터널 PID를 유지한 채 서버만 재시작하고 ChatGPT 앱 리소스를 새로 고쳤다.
- 수정 전 b4f0e9f의 CI #200371은 deploy-smoke 포함 5개 작업 통과. 수정본 CI와 S25 화면 재확인은 별도 진행한다.
- 이번 브라우저 환경에서는 모바일 viewport 설정이 실제 창에 적용되지 않아 수정본의 모바일 적합성은
  S25 재확인 전까지 완료로 간주하지 않는다. 데스크톱에서 견적·팝업·동일 너비 버튼을 확인했다.

### S25 최종 재확인

같은 날 사용자가 새 ChatGPT 대화에서 수정 화면을 확인하고 다음 4개 항목 모두 정상으로 보고했다.
접힌 필터와 짧아진 검색 화면, KRW 없는 원화 표시, 할인·배송비·예상 총액,
장바구니 총액과 하단 버튼 표시를 확인했다. 앞선 기능 정상 확인과 합쳐 실기기 QA를 통과했다.
최신 원격 CI와 Jira 완료 상태는 Draft MR !103에서 관리한다. 실제 Core 연동 검증은 이 결과에 포함하지 않는다.
