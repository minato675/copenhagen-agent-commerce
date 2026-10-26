# Copenhagen Reimagined 이전 내역

2026-09-27 기준 비교 원본은 `Copenhagen Reimagined`, 대상은 이 저장소의 `mobile/`이다.
최신 원격 기준은 `develop@37d7ff7`이며, 비교에는 작업 전 mobile에 있던 구매 인계 관련 미커밋 수정도 포함했다.

GitLab에 `feature/S15P21A207-512-mobile-reimagined`를 먼저 생성한 뒤 로컬 추적 브랜치로 가져와 `pull --ff-only`를 완료하고 복사를 시작했다.
사용자가 요청한 한 브랜치에서 13개의 논리적 작업을 각각 Jira Task와 커밋으로 연결했다.
담당자는 박건우, 스프린트는 `특화 6주차(9/21~27)`(58083)이다.

- [통합 Merge Request !198](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/merge_requests/198)
- [작업 브랜치](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/tree/feature/S15P21A207-512-mobile-reimagined)

## Task별 비교

| Jira Task | 기존 mobile | 개선·추가된 동작 |
|---|---|---|
| [S15P21A207-512](https://ssafy.atlassian.net/browse/S15P21A207-512) — 공통 API 계약 및 읽기 재시도 안정화 | 개별 HTTP 호출과 평면 승인·주문 모델 | 허용된 읽기 요청의 제한된 재시도와 Retry-After 처리; Core/Pay의 복수 판매처·상품 식별자·배송지·생체 인증 응답 모델 반영; Core 변경 명령과 주문 v2·표시 정보 조회 경로 분리 및 오류 안내 보완 |
| [S15P21A207-513](https://ssafy.atlassian.net/browse/S15P21A207-513) — 복수 판매처 승인 검증 및 구매 요청 복구 | 기본 구매 인계와 단일 판매처 중심 승인 상태 | 승인 직전 판매처별 주문·상품·금액·카드 조건을 재조회하고 변경 시 재확인; 공동 승인 ID 중복 제거와 동일 요청 재전송의 멱등성 유지; 배송지 확정 명령 복구, 늦은 응답 배제, Core 구매 결과에 따른 완료·부분 완료·처리 중 구분 |
| [S15P21A207-514](https://ssafy.atlassian.net/browse/S15P21A207-514) — 생체 인증 등록·해제 및 서버 상태 동기화 | 로컬 기기 키 중심의 등록·해제 | 서버 credential과 Android Keystore 키의 일치 여부 확인; 재등록 전 기존 서버 등록 해제를 확인하고 실패 시 로컬 키 유지; 기기 인증 취소·잠금 처리와 승인·구매 복구 정보의 암호화 보관 |
| [S15P21A207-515](https://ssafy.atlassian.net/browse/S15P21A207-515) — 상품 이미지 지연 조회 및 요청 공유 | 승인·구매·주문 표시용 공통 이미지 보완 없음 | 승인·구매·주문 화면에 원본 계약을 보존하며 상품 이미지만 보완; 상품 식별자·판매처·옵션 검증, 기존 데이터의 엄격한 이름 매칭; 대표 이미지 우선 조회, 진행 중 요청 공유·세션 캐시, 메타데이터 2개·상품 이미지 4개 동시 요청 제한 |
| [S15P21A207-516](https://ssafy.atlassian.net/browse/S15P21A207-516) — 구매 기록 및 주문 v2 조회 개선 | 승인·주문 기록 중심, Core v1 주문 조회 | Core 구매 목록을 먼저 표시하고 화면에 보이는 카드의 승인 정보·이미지를 후속 조회; 요청·완료·처리 중 금액과 통화별 합산, 중복 승인 제거, 검색과 필터 지원; 주문 v2 스냅샷·상품 ID 조회, 경로 404에만 60초간 v1 호환, 페이지·상세 갱신의 이전 결과 유지 |
| [S15P21A207-517](https://ssafy.atlassian.net/browse/S15P21A207-517) — 디자인 시스템·브랜딩 및 공통 모션 적용 | 기본 화면·아이콘과 제한된 공통 시각 규칙 | 색·타이포그래피·공통 버튼·입력·아이콘·상태 표현을 통일; 브랜드 로고, 여백을 확보한 adaptive 런처, 알림 아이콘과 항구 시작 애니메이션 적용; 동작 감소 설정, 공유 요소, 로딩·기본 선택 전환과 접근성 의미 적용 |
| [S15P21A207-518](https://ssafy.atlassian.net/browse/S15P21A207-518) — 카드 등록 입력 및 지갑·카드 관리 개선 | 단순 카드 목록·등록 폼 | 카드 번호·유효기간·CVC 입력과 오류 표시·포커스·동의 흐름 정리; 실제 등록 카드에 기반한 카드 표현, 기본 카드 선택·삭제 동작 개선; 홈의 승인할 결제, 카드와 최근 활동·바로가기 정보 계층 정리 |
| [S15P21A207-519](https://ssafy.atlassian.net/browse/S15P21A207-519) — 알림 진입·세션 복원 및 탭 탐색 통합 | MainActivity 중심 알림 진입, 카드·배송지·기록·설정 탐색 | 지갑·요청·기록 탭, 로그인 입력 및 공유 AppModel의 기능 연결; 승인·배송지 선택·결제 결과 알림을 구분하고 중복 전달을 억제; 알림 전용 Activity에서 로그인·인증·뒤로가기·주 화면 복귀·프로세스 복원 처리; 기존 package/Firebase 연결을 유지하는 demo 빌드와 격리된 preview 빌드 구성 |
| [S15P21A207-520](https://ssafy.atlassian.net/browse/S15P21A207-520) — 승인·PIN 및 배송지 선택 화면 개선 | 승인·인증·결과 개별 화면과 기본 배송지 선택 | 승인 검토·인증·대기·영수증을 연속된 결제 화면으로 표시; 상품·판매처별 금액과 카드 변경, 생체 인증 취소 후 PIN 대체 및 섞인 숫자 키패드; 배송지 목록·등록·우편번호 검색과 구매 재개·만료·재시도 안내 개선 |
| [S15P21A207-521](https://ssafy.atlassian.net/browse/S15P21A207-521) — 구매·승인·주문 기록 및 설정 화면 개선 | 승인·주문 중심 목록과 평면 설정 | 구매 기록·승인 기록·주문 배송을 별도 탭으로 표시하고 검색·상태 필터 유지; LazyColumn으로 보이는 기록만 구성하고 대표 이미지·상품명·판매처 결과 표시; 구매 상세와 승인·주문 상세 이동, 통화별 금액·조회 실패 이전 결과 표시; 프로필·보안·알림·서버 설정의 그룹과 상태 안내 정리 |
| [S15P21A207-522](https://ssafy.atlassian.net/browse/S15P21A207-522) — 상품 탐색·장바구니 및 연결 관리 화면 개선 | 기존 상품·장바구니·AI 연결 기능의 단순 배치 | 상품 목록·검색 필터·판매처 비교와 상세 옵션 선택의 화면 계층 정리; 장바구니 수량·삭제·예상 금액과 동시 작업 표시 개선; AI 연결 권한·연결 해제 확인 화면을 공통 디자인으로 통일 |
| [S15P21A207-523](https://ssafy.atlassian.net/browse/S15P21A207-523) — 격리 preview 및 재현 도구 이전 | 기본 구문·규칙 점검 도구 | 별도 .preview 패키지와 loopback fixture 서버·합성 시나리오 소스 이전; 승인·알림·배송·기록·카드·접근성·레이아웃 재현 도구 보존; 원본 테스트·검증 도구를 실행하지 않고 코드로만 이전; HTTP 읽기 재시도 횟수에 맞춘 기존 기능 fixture 응답 보존 |
| [S15P21A207-524](https://ssafy.atlassian.net/browse/S15P21A207-524) — Reimagined 비교 내역 및 연동 문서 현행화 | 이전 화면·계약·검증 설명 | 기존 mobile과 현재 원본의 차이를 13개 Task 및 커밋에 대응시켜 문서화; 현재 API 계약·화면 진입·알림·preview/demo 빌드 설명 현행화; 미커밋 작업 보관, 민감 설정·빌드 산출물 제외, 이번 이전의 테스트 미실행 명시 |

## 코드와 커밋 경계

각 커밋 제목은 `<type>: [Jira-key] <한글 작업명>`이다. 공통 모델·기능 상태·UI·진입부가 단계별로 이어지므로 현재 앱의 이전 완료 상태는 전체 13개 커밋을 기준으로 한다.

| Task | 주요 파일 | 커밋 |
|---|---|---|
| S15P21A207-512 | `data/Api.kt`, `data/DecorationReads.kt`, `data/Http.kt`, `data/Models.kt`, `data/ReadRetry.kt` 외 7개 | [3bced23](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/3bced2378a1db0b5efe6fe89fb0a77d979185b79) |
| S15P21A207-513 | `data/ApprovalReview.kt`, `data/Purchase.kt`, `features/Approvals.kt`, `features/PaymentOutcomes.kt`, `features/Purchases.kt` 외 5개 | [9116b4f](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/9116b4f5e7ac3a6cfa58be7c676209549ed93058) |
| S15P21A207-514 | `data/Store.kt`, `features/BiometricEnrollment.kt`, `features/BiometricRevocation.kt`, `features/Biometrics.kt`, `test/features/BiometricEnrollmentTest.kt` 외 1개 | [c08302c](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/c08302cd7cc34f1331db245855a3dd8f19944a63) |
| S15P21A207-515 | `features/ProductImages.kt`, `test/data/ProductImageContractTest.kt`, `test/features/ProductImagePaginationTest.kt`, `test/features/ProductImagesTest.kt` | [cffdb3a](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/cffdb3a68bf8cfc118fcb1277e19145e68359e4a) |
| S15P21A207-516 | `data/OrderHistoryReads.kt`, `data/PurchaseHistory.kt`, `features/Account.kt`, `features/PurchaseHistory.kt`, `test/data/OrderHistoryReadsTest.kt` 외 4개 | [7317f2e](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/7317f2e40ecd4529967eca95b1484242a81a3846) |
| S15P21A207-517 | `ui/Brand.kt`, `ui/Components.kt`, `ui/DefaultSelectionMotion.kt`, `ui/Glyphs.kt`, `ui/HarborLaunch.kt` 외 27개 | [40e8056](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/40e8056720da5289212139dd9c474d55df79110d) |
| S15P21A207-518 | `features/Cards.kt`, `ui/CardAppearance.kt`, `ui/CardEntryInput.kt`, `ui/CardEntryScreen.kt`, `ui/CardScreens.kt` 외 2개 | [0cb7707](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/0cb770702f229cdd80989ae975bbbb8732a5fbbf) |
| S15P21A207-519 | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `AppModel.kt`, `ApprovalActivity.kt`, `ApprovalMessagingService.kt` 외 13개 | [a83b2aa](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/a83b2aa0fbc4b2bb010427fd9222e03a3dcaf0c0) |
| S15P21A207-520 | `ui/AddressScreens.kt`, `ui/ApprovalScreens.kt`, `ui/PinEntry.kt`, `ui/PurchaseScreens.kt`, `test/ui/PinEntryTest.kt` | [c0d70af](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/c0d70afb50fc07586bc5d15a49f4f04a93efa78b) |
| S15P21A207-521 | `ui/HistorySettingsScreens.kt`, `ui/OrderItemRows.kt`, `ui/PurchaseHistoryScreens.kt` | [c4a496d](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/c4a496d3751644c3d3c85fbbaa92eb02b4ba7d99) |
| S15P21A207-522 | `ui/CartScreen.kt`, `ui/ConnectionScreen.kt`, `ui/ProductScreens.kt` | [c873481](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/c8734816813a7db424cffd78dda447f6840351eb) |
| S15P21A207-523 | `app/src/preview/AndroidManifest.xml`, `app/src/preview/kotlin/com/copenhagen/wallet/PreviewActivity.kt`, `app/src/preview/kotlin/com/copenhagen/wallet/PreviewApprovalActivity.kt`, `app/src/preview/kotlin/com/copenhagen/wallet/PreviewNotificationReceiver.kt`, `app/src/preview/res/xml/preview_network_security.xml` 외 38개 | [ee59257](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/commit/ee5925720d26430f852a378cfe0765ed0e936ee3) |
| S15P21A207-524 | `.gitignore`, `DESIGN.md`, README·계약·화면·검증·비교 문서 | 이 문서를 포함하는 S15P21A207-524 커밋 |

## 이전 범위와 확인 방법

- 앱 main/test/preview 소스, Android 리소스, Gradle 구성, 브랜드 원본, 재현 도구를 원본대로 이전했다. 변하지 않은 공통 파일은 그대로 유지했다.
- 원본과 대상의 실행 코드·테스트·리소스·도구는 파일 내용으로 대조했다. 빌드 산출물을 가져와 소스가 맞는 것처럼 취급하지 않았다.
- `.gitignore`에는 생성 APK·화면 검증 자료 제외 규칙을 덧붙였다. 브랜드 README의 이전 작업 기록 링크는 이 문서로 바꿨다. 앱 실행 코드를 이전을 위해 다시 작성하지 않았다.
- 대상 전용 README와 계약·화면 문서는 현재 구현에 맞춰 갱신하고, 과거 검증 기록은 해당 날짜의 기록으로 보존했다. 원본의 대량 화면 캡처·로그·APK 및 작업 지시 프롬프트는 이전 대상에 넣지 않았다.
- 원본과 같았던 `environment.properties`, `local.properties`, `app/google-services.json`은 로컬에 유지하고 커밋에서 제외했다. 서명 키·캐시·빌드 폴더도 제외했다.
- 작업 전의 미커밋 파일과 패치는 저장소 밖에 보관했다. 기존 구매 인계 기능은 현재 원본의 구매·승인 흐름으로 이전했고, 모바일 밖의 `docs/architecture/copenhagen-system-map.*`는 건드리지 않았다.

사용자 요청에 따라 이번 이전에서 **빌드·테스트·Lint·fixture·에뮬레이터·실서버 요청을 실행하지 않았다**.
기존 테스트와 재현 도구는 소스 보존 대상이며 새 통과 결과를 주장하지 않는다. 파일 내용 확인은 앱 실행 검증과 구분한다.

## 유지되는 제품 경계

- Pay 승인은 Core의 실제 결제 완료와 구분한다. 금액·통화·판매처 참조가 일치할 때만 데이터를 결합한다.
- 주문 v2의 이미지 스냅샷을 우선 사용하며 v1 전환은 경로 미배포 404에 한정한다. 구매-주문 연결 ID나 구매 전체 기간 조회를 만들어내지 않는다.
- 항구 시작 화면은 약 2.18초의 native Canvas/Compose 타임라인과 200ms 종료 전환으로 구성된다. 프로세스당 한 번 재생하고 화면 회전·백그라운드 전환 시 진행 상태를 보존하며 동작 감소 설정을 반영한다.
- 화면 캡처 허용은 명시적인 demo/preview 구성에 한정한다. 기존 패키지·Firebase 등록과 일반 앱의 보안 창은 유지한다.
- 실제 서버 배포·기기 실행 상태는 이번 이전에서 새로 확인하지 않았다. 상세한 클라이언트 계약은 [CONTRACT.md](CONTRACT.md), 화면과 호출부는 [SCREENS.md](SCREENS.md)를 따른다.
