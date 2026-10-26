# Story 431 검증 기록

기준 develop: ed978d9. Task 434의 로컬 재현은 실제 MCP transport + 서명된 테스트 JWT + Fake Core를 사용한다.
운영 계정의 토큰이나 개인키를 테스트에 복사하지 않는다.

## 확인된 경로

- catalog:read만 가진 JWT로 get_cart 호출 시 cart:read challenge가 발생한다.
- add_cart_item은 별도의 cart:write를 요구하므로 조회 동의만으로 담기는 허용되지 않는다.
- 인증 Tool 오류에 outcome이 없어 LIVE UI가 UNKNOWN으로 처리한다.
- LIVE UI의 공통 대기 제한은 10초이며 호스트 동의 대기도 여기에 포함된다.
- Core 내부 상품 검색의 판매처 조회 방식과 실제 소요 시간은 별도 측정한다. 운영 지연 원인은 미확정이다.

## 계측

MCP timing 로그는 서버 생성 requestId로 Tool 전체, 서비스 토큰 취득, Core HTTP 구간을 연결한다.
X-Request-Id로 같은 ID를 Core에 전달한다. 인자·검색어·사용자·토큰·본문을 로그에 남기지 않는다.
Tool _meta의 copenhagen/diagnostics에는 requestId와 serverMillis만 포함한다.
위젯 개발자 콘솔의 copenhagenDiagnostics()는 최근 200개 호출의 UI 대기 시간과 서버 시간을 제공한다.
UI 시간에는 호스트 전달과 동의 시간이 포함되므로 서버 시간과 동일시하지 않는다.

## 배포 후 필요한 측정

동일 상품·판매처·동시 요청 수로 프로세스 시작 직후 cold와 이후 warm을 구분해 각 30회 이상 측정한다.
검색·상세·장바구니별로 성공/인증/실패를 분리하고 p50/p95를 계산한다.
실제 ChatGPT 클릭→표시, OAuth 갱신, EC2 재시작 검증은 이 로컬 기록에 포함하지 않는다.
운영 baseline을 수집하기 전 수치 개선율이나 Task 완료를 선언하지 않는다.

## 수정 후 로컬 검증

- 435: prepare_cart가 cart:read·cart:write를 함께 요구하며 실제 동작은 조회뿐이다.
  기존 catalog 권한을 challenge에서 유지한다. 상품 검색만으로 구매 권한을 요청하지 않는다.
  Core 토큰 갱신 단위 테스트 17개 통과. PostgreSQL 트랜잭션 테스트 7개는 DB 미설정으로 건너뛰었다.
- 436: 실행 전 인증 거절은 NOT_APPLIED, handler 진입 이후 인증 오류는 UNKNOWN이다.
  UI는 인증 후 이어서 처리, 미전송 요청 취소, 위젯 재진입 복구를 지원한다.
  앞선 전송이 UNKNOWN이면 이후 인증 실패로도 요청을 폐기하지 않는다.
- 437: 내부 검색만 bounded pool로 판매처를 동시에 조회한다. 앱 검색 API는 유지한다.
  완료 순서와 무관하게 판매처 순서로 결과를 합치고, 전체 제한 시간이 지나면 완료된 결과를 보존한다.
  동일 조회가 진행 중일 때만 합치며 완료된 가격·재고 결과를 캐시하지 않는다.
  최초 장바구니 준비 이후 중복 준비 조회를 줄이고, 인증 오류가 나오면 다시 권한을 확인한다.

### 제어된 지연 벤치마크 (운영 측정 아님)

CatalogSearchFanoutTest: 판매처 3개, 각 테스트 응답 지연 25ms, 같은 데이터, 동시 사용자 1명.
순차 기준 알고리즘과 병렬 구현을 같은 테스트 프로세스에서 교대로 실행했다.

| 조건 | 순차 | 병렬 |
| --- | ---: | ---: |
| 첫 호출 1회 | 78ms | 28ms |
| warm 30회 p50 | 77ms | 26ms |
| warm 30회 p95 | 78ms | 27ms |

이 수치는 외부 HTTP·DB·ChatGPT를 포함하지 않는다. 실제 프로세스 cold start나 운영 개선율이 아니다.
판매처 Profile discovery의 synchronized 구간은 cold 경로에 남아 있으므로 실제 로그로 추가 확인한다.

### 설정·배포 확인

Core 신규 설정: CORE_CATALOG_SEARCH_CONCURRENCY=6, CORE_CATALOG_SEARCH_QUEUE_CAPACITY=24,
CORE_CATALOG_SEARCH_BUDGET=4s (기본값). budget은 MCP CORE_REQUEST_TIMEOUT(기본 5s)보다 짧게 유지한다.
커스텀 값은 core-service 컨테이너 환경에 전달해야 한다. 로컬 .env 파일만 바꾸면 자동 주입되지 않는다.
MCP와 Core 모두 해당 커밋 이미지로 배포해야 한다. DB migration이나 개인키 재생성은 필요하지 않다.
새 prepare_cart 도구 노출을 위해 배포 후 ChatGPT 연결 도구 목록을 새로고침한다.

위젯 대기 제한: prepare_cart 120초, 조회 15초, 변경 30초. 변경 timeout은 서버 취소가 아니다.
인증 준비 단계에서는 취소할 수 있으며 늦게 도착한 준비 응답이 변경을 실행하지 못하게 한다.
실제 호스트 재연결·refresh·EC2 재시작, 운영 cold/warm p50/p95와 컨테이너 검증은 배포 후 남아 있다.


## S15P21A207-444 — 만료된 구매 요청 재시작

동일 cartId/cartVersion의 저장된 영수증을 계속 재사용해 만료 후 구매 요청을 새로 만들 수 없던 문제를 수정했다.
서버가 EXPIRED를 반환한 경우에만 구매 상태 화면에 `다시 구매 요청`을 표시한다.
버튼을 누르면 서버 상태를 다시 확인한 뒤 같은 장바구니 버전과 새 멱등 키로 요청한다.
로컬 만료 시각만으로 판단하지 않으며 진행 중·완료·부분 완료·실패 상태 및 조회 실패는 새 요청을 만들지 않는다.
결과 불명 요청이 남아 있으면 기존 키로 복구하며, 연속 클릭은 한 번만 전송한다.
Core의 장바구니 버전 검증은 유지한다. 다른 화면에서 장바구니가 변경됐다면 새로 조회한 장바구니에서 진행한다.

검증: MCP 서버 테스트 241개 통과(실패·건너뜀 0), UI 전체 74개 통과(추가 회귀 11개), UI 번들 재생성 및 소스 일치, git diff --check 통과.
기존 431의 백엔드 전체 검증 수치는 위 기록을 참고한다. 이번 변경은 MCP UI·테스트·생성 번들에 한정한다.
배포 시 새 MCP 이미지가 필요하며 이 수정만을 위한 EC2 환경 변수·DB 변경은 없다.
실제 ChatGPT에서 만료 상태 확인 → 다시 구매 요청 → 앱 알림 수신은 배포 후 확인해야 한다.
