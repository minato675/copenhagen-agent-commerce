# QA2 상품 탐색

## 책임 경계

합의한 실제 요청 흐름은 **MCP → Core → 쇼핑몰 UCP**이다. MCP에서 쇼핑몰을 직접 호출하지 않는다.
`CoreProductCatalog`와 `CoreProductDetails`는 MCP 내부 모델을 사용하는 교체 가능한 경계이다. 실제 Core endpoint,
인증/연결 해제 검증, 지원 필터와 응답 계약은 아직 확정되지 않았다.

현재 `src/test`의 `QaCoreServer`와 `QaCatalogStore`가 Core를 대체한다. 고정된
`/fixture/catalog/search`, `/fixture/catalog/detail`은 테스트 전용 HTTP 경로이며 운영 API 명세가 아니다.
실제 연결 시 별도 HTTP 어댑터에서 합의된 Core 요청/응답을 내부 모델로 변환하고,
지원하지 않는 필터는 Tool schema와 UI에서도 함께 제거한다. Mock 검색/가격 계산을 운영으로 옮기지 않는다.
Core·UCP 담당 모듈 및 공용 계약은 수정하지 않았다.

`mcp.catalog.enabled=false`가 기본값이다. 활성화하려면 어댑터 Bean과 명시적인
`mcp.catalog.required-scopes`가 모두 필요하다. QA 인증·Mock 서버·로컬 미리보기는 운영 JAR에서 제외된다.

## Task 317

- `search_products`: 전체 검색 조건을 Core에 전달하는 데이터 도구. 생략한 조건은 제거한다.
- `display_products` / `open_copenhagen`: 해시가 포함된 상품 UI를 표시한다.
- 카드 안의 재검색은 `search_products`를 호출해 같은 UI를 갱신한다.
- 상품군은 선택 사항이다. `category`를 생략하거나 빈 값으로 보내면 여러 상품군을 함께 검색하고 페이지로 반환한다.
  명시한 상품군 필터는 유지한다. 한 페이지 기본 4개, 최대 20개, 판매처·가격·브랜드 필터와 정렬을 지원한다.
  Mock은 상품군이 여러 개라는 이유로 `CLARIFY`를 반환하지 않는다. 기존 Core 응답 호환을 위해
  schema와 소비자 검증의 `CLARIFY` 지원은 유지하며, 해당 응답을 받은 경우에만 상품군을 확인한다.
- 인증된 사용자/연결, 응답 schema/128 KiB 한도, 페이지·조건 일치, 공개 ID, HTTPS 이미지 참조를 검증한다.
- 빈 결과와 일부 판매처 실패를 구분한다. 상품 텍스트는 DOM textContent로 표시한다.
- 이미지 호스트는 제공 데이터의 5개 CDN으로 제한한다. 실제 Core 이미지 계약이 바뀌면 CSP와 UI 허용 목록을 같이 갱신한다.

## 로컬 실행

Python 3 표준 라이브러리와 Java 21을 사용한다. 저장소 루트에서 실행한다.

```powershell
python backend/apps/mcp-server/scripts/import-qa-catalog.py "C:\경로\korean_products_clean_bundle.zip"
powershell -NoProfile -ExecutionPolicy Bypass -File backend/apps/mcp-server/scripts/start-qa.ps1 -Stage 2
```

미리보기: http://127.0.0.1:8092/qa · MCP: http://127.0.0.1:8092/mcp

중지: `powershell -NoProfile -ExecutionPolicy Bypass -File backend/apps/mcp-server/scripts/stop-qa.ps1 -Stage 2`

QA1은 기존 8091 포트와 `build/qa`, QA2는 8092와 `build/qa2`를 사용한다.
QA2는 호출마다 미리보기 → MCP HTTP → Mock Core HTTP를 왕복한다.
일회성 가상 신원은 4시간 후 만료된다. 재시작하면 새 신원을 발급한다.
실제 계정/결제 접근은 없으며 외부 인증 헤더를 받지 않는다.

한글 경로에서 Windows Gradle test worker가 클래스를 찾지 못하면 같은 작업 폴더를 가리키는
ASCII 경로 junction에서 Gradle과 실행기를 실행한다. 이는 다른 checkout/브랜치가 아니다.
개발 검증 환경에서는 `C:\Users\SSAFY\.codex\build-paths\S15P21A207`을 현재 프로젝트 폴더에 연결했다.

원본 SQLite는 read-only로 열며 생성된 1,408개 상품 데이터는 Git에서 제외되는 `build/qa2/catalog.json`에만 저장한다.
가상몰 A/B/C 가격은 상품 ID와 판매처에 기반한 결정적 ±7% 변형 후 100원 단위로 절삭한다. 실제 가격/재고가 아니다.
수집 데이터가 모르는 정보는 UNKNOWN/null로 유지한다. 비교 사이트 구성은 검증된 판매 옵션으로 승격하지 않는다.

## 검증 기록 — 2026-09-16, Task 317

- MCP Java 테스트 162개, 실패 0; `:apps:mcp-server:checkstyleMain :apps:mcp-server:checkstyleTest` 통과.
- Node 테스트 10개, 실패 0; UI source/bundle 생성 확인.
- Python importer 테스트 2개, 실패 0; 원본 DB 불변, 비교 구성 제외, 가격 결정성 확인.
- 실제 제공 데이터로 로컬 서버 기동, 검색/페이지 2 이동/판매처 필터 변경 시 페이지 1 복귀/가격 비교 팝업 확인.
- Chrome 412px 및 320px 폭에서 4개 카드, 긴 상품명 줄임과 가로 넘침 없음 확인.
- ChatGPT Android/S25 실기기 QA와 실제 Core/UCP 연동은 아직 미실행이다.
- Story는 실기기 QA 전 완료하지 않는다.

## Task 318

`get_product_detail`은 확인된 상품/판매처/옵션 공개 ID만 받고 새 UI를 표시하지 않는다.
상품 상세·설명·스펙, 수집된 옵션, 확인 가능한 판매 상태를 반환한다. 응답은 동일한 사용자/연결과
요청 참조에 속해야 한다. 옵션 선택 상태(REQUIRED/VALID/INVALID/UNKNOWN)를 검증하며
필수 옵션 누락·유효하지 않은 옵션에는 확정 가격을 반환하지 않는다.
전체 유효 조합을 optionId 하나로 표현하며 임의 색상/사이즈 조합을 생성하지 않는다.

로컬 fixture는 원본 product_variants만 사용한다. 여러 variant가 있을 때 하나를 선택하도록 하는
규칙은 QA에서만 적용하며 실제 필수 옵션 여부는 Core 계약으로 교체한다.
최대 50개를 표시하고 잘린 경우 optionsComplete=false를 반환한다. 수량·배송비는 원본에서 확인되지
않으므로 null/UNKNOWN이다. 구매 불가 플래그를 품절로 바꾸지 않고 별도로 유지한다.

비교 팝업에서 판매처별 상세를 조회하고 옵션을 고르면 가격·상태를 재조회한다.
채팅 설명 버튼은 외부 상품 이름/설명 대신 검증된 공개 ID만 ChatGPT에 전달한다.
로컬 브라우저 미리보기에는 ChatGPT 대화가 없으므로 입력할 요청 문구를 표시한다.

운영 상세 도구 역시 `mcp.catalog.detail-enabled=false`가 기본값이다.
실제 CoreProductDetails Bean과 검색 경계/권한 설정을 함께 준비한 다음 활성화한다.

## QA2 터널

공식 tunnel-client와 권한이 있는 Secure Tunnel ID를 사용한다.
`start-qa-tunnel.ps1 -Stage 2 -TunnelId <ID> -ClientPath <공식 클라이언트 경로>`로 실행한다.
QA2 헤더 및 MCP 왕복 검증 후 8092 전용 프로필을 만들고 런타임 키를 보안 콘솔에서 입력받는다.
키는 저장하지 않는다. `-Check`는 로컬 준비만 확인하며 ChatGPT 연결 성공을 의미하지 않는다.
같은 Tunnel ID의 기존 데몬이 있다면 소유한 기존 데몬을 먼저 종료하고 새 프로필로 연결한다.
ChatGPT에서 앱 도구 목록을 새로 고친 뒤 검색/상세 도구가 보이는지 확인한다.

## 검증 기록 — 2026-09-16, Task 318

- 전체 backend `checkstyleMain checkstyleTest build` 성공. MCP 테스트 168개, 실패/skip 0.
- 다른 모듈 포함 테스트 보고서 796개 중 Core 65개 skip, 실행된 검사 실패 0. skip된 검증까지 통과로 간주하지 않는다.
- UI Node 테스트 12개 및 번들 일치 검사, importer Python 테스트 2개 통과.
- 운영 bootJar에 QA 실행기/Fake 인증/상품 데이터 미포함 확인.
- 원본의 두 옵션을 가진 상품에서 필수 옵션 선택 전 가격 미확정, 옵션 선택 후 가격 변경 확인.
- 공개 ID만 담긴 상세 채팅 요청과 배송·수량 미확인 표시 확인.
- 서버 중단 시 재시도 버튼, 서버 재시작 후 같은 팝업에서 선택 옵션/가격 복구 확인.
- 로컬 ChatGPT 연결과 S25 실기기 QA는 대기 상태다. 실제 Core/UCP 통신은 아직 수행하지 않는다.

최신 develop `f736f82` rebase 후 전체 backend 검증도 통과했다.
주소 관리 테스트가 추가되어 전체 보고서는 819개, Core skip 68개이며 실행 검사 실패는 0이다.
MCP 자체는 168개 모두 실행해 통과했다. 최초 CI의 deploy-smoke는 실행 중 갱신된 develop을
포함하지 못해 prepare 단계에서 실패했으므로 최신 기준으로 다시 검증한다.

제공 데이터 전체 점검에서 같은 이름/값의 중복 스펙이 발견되어 importer에서 중복 제거했다.
서로 다른 값은 보존한다. 수정된 데이터 전체에 대해 실제 MCP 검증 경계로 상세/옵션 15,339건과
59개 상품군의 검색 376페이지를 점검해 모두 통과했다. 원본 DB는 변경하지 않았다.

## ChatGPT 연결 검증 — 2026-09-16

- 같은 Tunnel ID의 기존 QA1 데몬 종료 후 QA2(8092)로 연결하고 앱 도구 목록을 갱신했다.
- ChatGPT 앱 이름은 `Copenhagen QA2`이며 검색·화면 표시·상세 조회 도구를 확인했다.
- 데스크톱 Chat 대화에서 미쟝센 샴푸 검색, 동일 카드의 가상몰 3곳 가격 비교를 확인했다.
- 위젯 안의 상세 조회 오류를 재현해 UI 수신 경계에서 누락된 nullable 가격·수량·배송비를
  null(미확인)로 정규화했다. 0원과 구분하며 잘못된 값·식별자와 서버의 엄격한 Core 검증은 유지한다.
- 수정 후 필수 옵션 안내, 샴푸 선택 시 8,600원 갱신, 배송비·수량 미확인 표시를 확인했다.
- 상세 설명 버튼이 선택한 공개 상품/판매처/옵션 ID로 ChatGPT 후속 요청을 전달했다.
- 보완 후 MCP 테스트 168개와 Checkstyle, UI 테스트 13개 통과. 수정 전 `ea1c5ac`의
  원격 CI #200253은 deploy-smoke를 포함한 5개 작업 모두 통과했다.
- Work 대화 첫 시도는 이전 QA1 도구 정보를 참조했다. 위 성공 기록은 Chat 모드에서 확인한 결과다.
- S25 Ultra 실기기 확인과 수정 커밋의 원격 CI는 아직 완료 전이다. Story는 진행 중으로 유지한다.

동일 ChatGPT 카드에서 재검색할 때 초기 host tool result가 다시 도착해 사용자의 요청을 덮어쓰는
현상도 재현했다. UI 요청 진행 중인 경우와 현재 화면보다 오래되거나 같은 host 응답은 무시한다.
첫 화면 응답과 더 최신 host 결과는 계속 받을 수 있다. 회귀 테스트를 추가해 UI 테스트 14개와
MCP 테스트 168개가 통과했다.

수정 후 실제 ChatGPT에서 `샴푸` 재검색 → 상품군 확인 → `뷰티 생활` 선택 → 4개 카드 →
2페이지 → `가상몰 B` 선택 시 1페이지 복귀를 확인했다. 없는 검색어의 빈 결과 안내도 확인했다.
QA2 서버를 중단하면 10초 후 확인 시 오류와 재시도 버튼이 표시됐고, 서버 재시작 후 기존 카드의
재시도로 미쟝센 샴푸 2개를 다시 조회했다. 앞선 옵션 선택의 후속 채팅도 상세 설명으로 완료됐으며
추가 상품 UI는 생성되지 않았다. 이 기록은 데스크톱 Chat 검증이며 S25 확인을 대신하지 않는다.
