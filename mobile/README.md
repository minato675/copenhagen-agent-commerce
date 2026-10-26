# Copenhagen Android

Kotlin + Jetpack Compose, 단일 `app` 모듈이다. `Copenhagen Reimagined`의 현재 앱 코드를 모노레포 `mobile/`로 이전했다.
변경 사항은 [Task별 비교 내역](docs/REIMAGINED.md), API와 제약은 [연동 계약](docs/CONTRACT.md)에 정리했다.

## 실행 환경

- JDK 17 또는 21, Android SDK 35, min SDK 26.
- AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.1.20 / Compose BOM 2025.04.01.
- `local.properties`의 `sdk.dir` 또는 `ANDROID_HOME`으로 SDK 경로를 지정한다.
- Android Studio에서 저장소의 `mobile/` 폴더를 열고 Gradle Sync 후 빌드 변형을 선택한다.
- 포함된 Gradle Wrapper를 사용한다. 최초 실행 시 Gradle 배포본을 내려받는다.

| 변형 | 패키지 및 용도 |
|---|---|
| debug | `com.copenhagen.wallet`, 실제 Core/Pay/Firebase 설정 사용, 화면 캡처 차단 |
| demo | 동일 패키지·Firebase 설정·로그인 유지, 시연용 화면 캡처 허용 |
| preview | `com.copenhagen.wallet.preview`, `http://127.0.0.1:8787` fixture만 사용, 별도 앱 데이터·명시적인 테스트 표기 |
| release | 실제 설정 사용, 화면 캡처·평문 HTTP·PG Mock 카드 등록 차단 |

`preview`는 운영 Firebase 클라이언트를 초기화하지 않는다. 합성 fixture는 `tools/preview/`에 있다.
일반 앱의 로그인·카드·승인 결과를 가상 응답으로 대체하지 않는다.

2026-09-27 이전 작업에서는 사용자 요청에 따라 빌드·테스트·Lint·에뮬레이터를 실행하지 않았다.
원본 코드·테스트·리소스를 복사하고 파일 내용과 Git 범위만 확인했다. 기존 실행 기록은 [검증 기록](docs/VERIFICATION.md)에서 날짜별로 구분한다.

## 서버와 Firebase 설정

`environment.properties.example`을 `environment.properties`로 복사해 Core, Pay, PG의 기본 주소와 앱 링크 호스트를 설정한다.
앱의 로그인 화면 또는 설정 → 서버 설정에서도 주소를 바꿀 수 있다.

```properties
CORE_BASE_URL=https://core.example.com/
PAY_BASE_URL=https://pay.example.com/
PG_MOCK_BASE_URL=http://10.0.2.2:8082/
APP_LINK_HOST=links.example.com
```

위 주소·포트는 형식 예시다. 주소에는 API 경로를 붙이지 않고 실제 기기에서 도달할 수 있는 서버 주소를 사용한다.
빌드 속성 `-PCORE_BASE_URL=...` 등이 파일 기본값보다 우선하고, 앱에 저장한 주소가 빌드 기본값보다 우선한다.
PG 호출에는 Core Bearer 토큰을 보내지 않는다. 서버 변경 시 기존 계정의 알림 연결·세션을 정리한다.

Firebase 프로젝트에 Android 앱 `com.copenhagen.wallet`을 등록하고 `google-services.json`을 `app/`에 둔다.
로그인·저장된 세션 복원·명시적 재연결 시 FCM 등록 후 Firebase Installation ID를 Pay에 등록한다.
Firebase 설정 없이도 빌드는 가능하지만 알림 연결은 실패 상태로 표시된다.

현재 푸시는 배송지 선택·승인 요청·결제 결과를 구분하는 data-only 계약이다.
필수 필드와 알림 진입·중복 처리 규칙은 [FCM 계약](docs/CONTRACT.md#fcm-fid-등록과-승인-알림)을 따른다.
알림에서 받은 금액이나 상태로 승인하지 않고 인증된 서버 응답을 다시 읽는다.

## 주요 흐름

- **지갑 / 요청 / 기록**의 세 탭과 프로필 설정. 카드·배송지·상품·장바구니에 바로 접근한다.
- 여러 판매처의 주문·상품·금액을 확인하고 카드 선택 → 생체 인증 또는 PIN → 승인 결과로 이어진다.
- `APPROVED`는 승인 전달이다. 실제 완료·부분 완료·실패는 연결된 Core 구매의 판매처별 결과로 구분한다.
- 배송지 확정은 구매 ID·주소 ID·원래 장바구니 버전·UUID 멱등키로 재개한다. 불명확한 결과는 같은 명령을 복구한다.
- 구매 기록은 Core 결과를 먼저 표시한다. 화면에 보이는 카드의 상품명·대표 이미지를 보완하며 주문은 v2를 우선 조회한다.
- 생체 등록·해제는 서버 상태와 기기 키를 함께 확인한다. 미확정 승인·구매 참조는 암호화해 보관하고 인증 비밀은 디스크에 저장하지 않는다.
- 읽기 요청의 제한된 재시도와 변경 요청의 명시적 복구를 구분한다.
- 공통 디자인·로딩·카드 선택 모션, 브랜드 런처와 항구 시작 애니메이션을 적용했다.

## 코드 위치

| 수정 대상 | 위치 (`app/src/main/kotlin/com/copenhagen/wallet/` 기준) |
|---|---|
| API·인증 갱신·안전한 읽기 재시도 | `data/Api.kt`, `Http.kt`, `ReadRetry.kt`, `DecorationReads.kt` |
| 응답·승인 조건·금액 | `data/Models.kt`, `ApprovalReview.kt`, `Purchase.kt`, `PurchaseHistory.kt` |
| 카드 등록·승인·구매 재개 | `features/Cards.kt`, `Approvals.kt`, `Purchases.kt`, `PaymentOutcomes.kt` |
| 구매·주문 기록·상품 이미지 | `features/PurchaseHistory.kt`, `Account.kt`, `ProductImages.kt`, `data/OrderHistoryReads.kt` |
| 생체 인증·보안 상태 | `features/Biometrics.kt`, `BiometricEnrollment.kt`, `BiometricRevocation.kt`, `data/Store.kt` |
| 알림 수신·전용 Activity·공유 세션 | `ApprovalMessagingService.kt`, `ApprovalNotifications.kt`, `ApprovalActivity.kt`, `WalletSession.kt` |
| 로그인·상태 연결·탐색 | `AppModel.kt`, `MainActivity.kt`, `ui/App.kt` |
| 공통 디자인·브랜드·모션 | `ui/Components.kt`, `Glyphs.kt`, `Motion.kt`, `Brand.kt`, `Harbor*.kt` |
| 각 화면 | `ui/*Screen*.kt`, [화면 대응표](docs/SCREENS.md) |

브랜드 원본은 `assets/brand/`, Android 리소스는 `app/src/main/res/`에 있다.
`tools/export-brand-assets.sh`는 원본에서 리소스를 내보내며 앱 빌드 중 이미지를 생성하지 않는다.

PG Mock 토큰화는 개발용이며 release 카드 등록은 차단한다. 실제 카드번호·CVC를 개발 PG에 입력하지 않는다.
약관 원문과 서버 동의 증빙 계약은 별도로 필요하다. 지원하지 않는 금융 기능·구매-주문 연결 관계는 추정하지 않는다.

`environment.properties`, `local.properties`, `app/google-services.json`, 서명 키, 빌드·APK·화면 검증 산출물은 Git에 포함하지 않는다.
기존 [미사용 API 목록](docs/UNUSED_APIS.md)은 당시 조사 기록이며 현재 구현 여부는 위 문서와 소스를 우선한다.
