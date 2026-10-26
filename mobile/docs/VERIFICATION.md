# 검증 기록

## 2026-09-27 — Copenhagen Reimagined 코드 이전

- 사용자 요청에 따라 빌드·단위 테스트·Lint·정적 감사 스크립트·fixture·에뮬레이터·실서버 호출을 실행하지 않았다.
- 기존 원본의 앱·리소스·테스트·재현 도구를 작업별로 복사했다. 테스트 파일이 추가되었다는 사실은 이번 이전에서 테스트가 통과했다는 뜻이 아니다.
- 복사 대상의 파일 내용과 Git에 포함되는 경로를 확인했다. 로컬 서버·SDK·Firebase 설정은 원본과 같았으며 변경하거나 커밋하지 않았다.
- 기존 mobile의 미커밋 파일은 작업 전에 별도 보관했고, 모바일 밖의 아키텍처 문서는 그대로 유지했다.
- 비교와 Jira Task 대응은 [Reimagined 이전 내역](REIMAGINED.md)에 기록했다. 아래 실행 결과는 각각의 과거 작업 시점에 해당한다.

## 2026-09-21 — 구매 인계 알림·배송지 선택

MR !181의 Core 구매 요청 계약에 맞춰 모바일 푸시 분기·배송지 선택·구매 상태 조회를 연결했다.

- JDK 21에서 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain` 성공.
- JUnit 86개 통과(기존 67개 + 구매 요청 14개 + 알림 진입 대상 5개). 실패·오류·건너뜀 0개.
- 새 테스트는 구매 목록의 배열 계약, 인증·경로·원래 장바구니 버전·UUID 멱등키, 명시적 배송지 선택, 응답 유실 후 GET과 동일 본문·키 재전송, 이미 재개된 요청의 POST 생략, 조회 실패 복구, 삭제된 주소·버전 충돌·만료, 판매처별 결과·부분 완료, 잘못된 응답, 세션 종료 후 늦은 응답 폐기와 취소 전파를 확인한다.
- 알림 테스트는 `approvalId`가 빈 구매 인계, 기존 승인 메시지 호환, 잘못된 종류·ID 거절, 종류별 ID 저장·복원을 확인한다. 실제 Android Activity/프로세스 복원 테스트를 대신하지 않는다.
- Android Lint 오류 0·경고 15, `python3 tools/audit.py` 정적 검사 59개 통과.
- 실제 Firebase 발송·알림 권한·기기 화면 이동, 주소 등록 후 복귀, Core/Pay/Merchant 전체 구매 E2E는 실행하지 않았다. 항상 배송지를 직접 선택하는 동작은 MR !181이 반영된 서버가 필요하다.

## S15P21A207-463 — 최종 UI 정리

- 상시 안내문을 제거하고 수량·삭제·새로고침을 아이콘으로 바꿨다. 예상 금액 안내와 AI 연결 메타데이터는 사용자가 펼칠 때만 표시한다.
- 아이콘에 접근성 설명을 유지하고 삭제·연결 해제 확인 및 busy 중 중복 요청 차단을 보존했다.
- JDK 21.0.11의 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain` 성공. JUnit 67개 통과, Lint 오류 0·경고 15, `tools/audit.py` 59개 통과.
- 실기기·에뮬레이터 화면/터치 검증과 실제 Core/Pay/Firebase E2E는 실행하지 않았다. 이 기록은 클라이언트 컴파일·단위/계약 테스트 결과다.

## S15P21A207-469 — 상품 상세·옵션 조회

JDK 21.0.11에서 debug 빌드·JUnit·Lint(`assembleDebug testDebugUnitTest lintDebug`, offline)와 `tools/audit.py`를 실행했다.

- 빌드 성공, JUnit 67개 통과, Lint 오류 0·경고 15, 정적 검사 59개 통과.
- 추가 테스트 10개: 인증/경로 인코딩/merchantId/optionId, 옵션별 가격·재고·배송 매핑, 판매처 변경 시 옵션 초기화, nullable 정보 보존, 품절·구매 불가, 200 NOT_FOUND와 HTTP 오류 구분/재시도, 잘못된 경로 거절, 늦은 옵션 응답·종료 후 응답 폐기, FOUND 불완전 응답 거절.
- 검색 조건·결과는 Catalog에 유지하며 상세 상태는 ProductDetails에서 별도로 정리한다. 실제 화면 뒤로 가기·옵션 클릭 E2E와 배포 서버 연결은 미검증이다.
- 검색·상세 화면은 안내문 대신 검색/필터/페이지/새로고침 아이콘과 선택 칩을 사용한다. 부가 상품 정보는 펼침 방식이며 아이콘에 접근성 설명을 제공한다.

## S15P21A207-468 — 상품 검색·가격 비교

JDK 21.0.11에서 debug 빌드·JUnit·Lint(`assembleDebug testDebugUnitTest lintDebug`, offline)와 `tools/audit.py`를 실행했다.

- 빌드 성공, JUnit 57개 통과, Lint 오류 0·경고 15, 정적 검사 59개 통과.
- 추가 테스트 9개: 인증된 POST 조건 매핑, 외부 envelope·nullable 가격·판매처별 동일 상품 ID, 페이지 교체/조건 초기화, 가격 범위/Long 초과 입력 거절, 실패한 페이지 재시도, 일부 판매처 실패, 늦은 응답·늦은 오류 폐기, 세션 정리 중 응답 도착.
- 공개 ProductService에서 브랜드·카테고리 필터 미지원을 확인해 해당 입력을 노출하지 않았다. 실제 서버/기기 화면 검증은 미실행이다.
- 사용자 요청에 따라 장바구니·로그아웃의 긴 안내를 짧은 상태 문구로 바꾸고 동일 테스트를 재실행했다.

## S15P21A207-467 — 장바구니 수량·삭제와 충돌 처리

JDK 21.0.11에서 debug 빌드·JUnit·Lint(`assembleDebug testDebugUnitTest lintDebug`, offline)와 `tools/audit.py`를 실행했다.

- 빌드 성공, JUnit 48개 통과, Lint 오류 0·경고 15, 정적 검사 59개 통과.
- 추가 테스트 9개: 연속 수량 변경의 최신 버전/합계, DELETE 쿼리·200 및 마지막 상품 삭제, 버전 충돌 후 재조회, 삭제된 항목의 재조회 실패/복구, 4xx 거절, 요청 수신 후 연결 종료로 응답 유실, 잘못된 수량/ID, 잘못된 204 응답, 취소 전파.
- 응답 유실 뒤에는 GET 성공 전 추가 쓰기가 없고 충돌 요청을 자동 재전송하지 않는 것을 확인했다.
- 중복 입력 차단은 기존 AppModel 직렬 실행과 busy UI를 사용한다. 실제 기기의 연속 클릭·삭제 확인·서버 동시 편집 E2E는 미검증이다.

## S15P21A207-466 — 장바구니 조회

JDK 21.0.11에서 debug 빌드·JUnit·Lint(`assembleDebug testDebugUnitTest lintDebug`, offline)와 `tools/audit.py`를 실행했다.

- 빌드 성공, JUnit 39개 통과, Lint 오류 0·경고 15, 정적 검사 59개 통과.
- 새 MockWebServer 테스트 4개로 cartId=null인 빈 장바구니, 인증된 조회, 버전·옵션·Int 범위를 넘는 금액, 세션 정리, 실패 후 재조회 및 불완전 응답을 확인했다.
- 실제 Core/AI 장바구니 공유 및 기기 화면 E2E는 미검증이다.

## S15P21A207-465 — AI 서비스 연결 관리

JDK 21.0.11에서 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 및 `python3 tools/audit.py`를 실행했다.

- debug 빌드 성공, JUnit 35개 통과. 새 MockWebServer 테스트 4개는 배열 매핑·nullable 메타데이터·세션 정리·DELETE 204 후 재조회·해제 실패·재조회 실패 후 복구를 확인한다.
- Android Lint 오류 0·경고 15, 정적 검사 59개 통과. 경고는 기존 저장소 코드 및 라이브러리 업데이트 안내다.
- 서비스 로고 표시에 Coil Compose 2.7.0을 추가했다. 기존 Kotlin/Compose/OkHttp 조합으로 컴파일했다.
- 실제 서버, 이미지 다운로드, 확인 대화상자 및 화면 이동 E2E는 실행하지 않았다.

## S15P21A207-464 — FID 해제와 세션 종료

JDK 21.0.11에서 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain` 및 `python3 tools/audit.py`를 실행했다.

- debug 빌드 성공, JUnit 31개 통과(기존 23개 + 로그아웃 7개 + FID 경로 마스킹 1개).
- FID 해제 → Core 로그아웃 → 로컬 정리 순서, 해제/로그아웃 실패, 10초 타임아웃, 각 원격 단계의 취소 후 정리를 검증했다.
- MockWebServer로 FID DELETE 경로·기존 계정 Bearer·204 빈 본문과 로그의 FID/토큰 마스킹을 확인했다.
- Android Lint 오류 0·경고 14, 정적 검사 59개 통과.
- 실제 Firebase/Pay 서버 해제 및 계정 전환 UI/E2E는 실행하지 않았다. 단위 테스트의 콜백 순서 검증과 서버 연동 성공을 구분한다.

## 2026-09-20 — 모노레포 mobile 이전

스토리 [S15P21A207-456](https://ssafy.atlassian.net/browse/S15P21A207-456)의 현재 Android 앱을 모노레포 `mobile/`로 이전한 뒤, 해당 위치에서 검증했다. 기존 샘플 프로젝트를 교체했으며 production Kotlin 20개, 리소스, JUnit 테스트 2개 파일과 검증 도구의 내용은 이전 전 소스와 동일하다.

실행 환경: JDK 21.0.11, Android SDK 35, Gradle 8.11.1. 의존성이 준비된 로컬 캐시를 사용해 다음 명령을 실행했다.

```sh
cd mobile
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 tools/audit.py
```

- Firebase·서버 로컬 설정이 없는 상태의 기본 빌드 성공.
- 기존 로컬 서버 설정과 Firebase 설정을 적용한 최종 빌드 성공. `processDebugGoogleServices` 실행 확인.
- debug APK 생성: `app/build/outputs/apk/debug/app-debug.apk`.
- JUnit 23개 통과: `HttpTest` 12개, `PayContractTest` 11개. 실패·오류·건너뜀 0개.
- Android Lint: 오류 0개, 경고 14개.
- 정적 검사 59개 통과.
- 저장소의 런타임 `data/` 제외 규칙이 앱 소스를 누락시키지 않도록 `mobile/.gitignore`에 Android `data` 패키지 예외를 추가했다.
- `local.properties`, `environment.properties`, `app/google-services.json`은 로컬에서만 보존하며 Git에 포함하지 않는다. 빌드 산출물·IDE 및 Gradle 캐시·서명 키도 제외한다.
- 실제 서버 E2E, Firebase 푸시 발송, Android Keystore·생체 인증 프롬프트와 실기기 화면 검증은 이번 이전 검증 범위에 포함되지 않는다. 남은 연동 조건은 스토리 및 기존 Jira 이슈에서 관리한다.

아래는 이전 소스에 포함되어 있던 과거 검증 기록이다. 테스트 수와 환경은 각 실행 시점을 기준으로 한다.

## 2026-09-17 — FCM 추가 및 초기 전달본 기록

### FCM 추가 후 검증

JDK 21, Android SDK 35에서 아래 명령을 실행했다. Firebase 설정 파일 없이 빌드했다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
python3 tools/audit.py
```

- debug APK 빌드 성공: `app/build/outputs/apk/debug/app-debug.apk`.
- 기존 JUnit 16개 통과: `PayContractTest` 8개, `HttpTest` 8개. FCM 실기기 테스트가 아니다.
- Android Lint 통과, 오류 0. 기존 코드와 라이브러리 버전 등의 경고는 남아 있다.
- 정적 검사 59개 통과, production Kotlin 20개 파일.
- Firebase FID 모드를 사용하므로 이전 토큰용 `onNewToken()` Lint 요구는 근거를 주석으로 남겨 해당 경고만 제외한다.
- 실제 Firebase 프로젝트 설정·FID 등록 API·푸시 발송·OS 권한 프롬프트·실기기 화면 진입은 미검증.
  `app/google-services.json`을 추가한 뒤 로그인, 전경/배경/종료 상태 알림 클릭, 알림 권한 거부를 검증해야 한다.

### 초기 전달본에서 수행한 검사

| 검사 | 결과 | 범위 |
|---|---|---|
| `tools/verify-rules.sh` | 107개 통과 | 실제 production `Rules.kt`를 Kotlin CLI로 컴파일·실행. PIN/아이디/비밀번호/이메일/카드 Luhn/유효기간/CVC/호스트/상태 표시 검증. |
| `tools/audit.py` | 59개 통과 | 28개 화면 라우트, 제거 대상 코드, 호스트, PG/Pay 분리, 멱등성 키 위치, XML, 민감정보 로그·가상 응답 부재를 정적 검사. |
| `tools/verify-syntax.sh` | 26개 Kotlin/Kotlin DSL 파일, 구문 오류 0 | Kotlin PSI 파서 검사. Android 타입 검사나 Compose 컴파일이 아님. |

아래 검사 명령을 프로젝트에 포함했다.

```sh
sh tools/verify-rules.sh
python3 tools/audit.py
KOTLIN_HOME=/path/to/kotlin sh tools/verify-syntax.sh
```

### 초기 전달본에서 작성했지만 실행하지 못한 테스트

`app/src/test/.../PayContractTest.kt` 8개, `HttpTest.kt` 8개 JUnit 테스트를 포함했다.
요청 본문 필드, 카드 원문 분리, PG Bearer 제외, Pay 키/본문 유지, 카드 선택 PATCH와 승인 POST 분리,
기기 헤더, 204, 오류 표시, 리다이렉트, 취소를 검사한다.
HTTP 테스트는 실제 `Http`와 JSON 빌더를 MockWebServer에 연결한다. 실제 PG·Pay 백엔드 통합 테스트가 아니다.
카드 등록 상태 머신 전체/Android Keystore/생체 OS 프롬프트/화면 클릭 E2E 테스트로 제시하지 않는다.

### 초기 전달본의 Android 빌드 시도

실행한 명령:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

결과: 종료 코드 1. 공식 Wrapper JAR 다운로드 단계에서 다음 오류로 중단됐다.

```text
Downloading official Gradle 8.11.1 Wrapper JAR...
java.net.UnknownHostException: raw.githubusercontent.com
```

당시 전달 환경에는 Android SDK도 설치되어 있지 않았다. 따라서 Gradle Sync, Android 타입/리소스 검사,
Compose 컴파일, JUnit 16개 실행, APK 생성, 에뮬레이터·실기기 동작을 검증했다고 주장하지 않는다.
공식 Wrapper JAR은 초기 ZIP에 없었다. 이번 빌드에서 고정 버전·SHA-256으로 공식 JAR을 준비했다.
인터넷 연결이 가능한 개발 PC에서 README 순서대로 준비·동기화·빌드해야 한다.

### 코드 규모

같은 기준인 `src/main/**/*.kt` 기준으로 기존 26개/2,889줄 → 19개/1,694줄.
빈 줄·주석을 포함한 물리적 줄 수이며 기능 완성률·빌드 검증률이 아니다.
런타임은 하나의 `app` 모듈이다. 초기 전달본에서 실제 요청에 필요 없는 추상화·설정 엔진·선행 알림 구현을 제거했고,
이번 변경은 기존 흐름에 FID 등록과 승인 알림 수신을 추가했다.

### 서버와 운영 범위

Notion 문서는 읽었지만 실제 배포 서버에는 접속하지 않았다. PG는 개발용 Mock API이며 실제 배포는 확인하지 않았다.
Core의 미확정 DTO, 약관 원문·증빙 부재, FCM 계약과 검증 한계, 앱 링크 서버 설정은 CONTRACT.md에 명시했다.
이 ZIP은 실행/수정 가능한 프로젝트 소스를 제공하려는 재작성본이며 운영 배포 완료나 인증·보안 심사를 받은 결과물이 아니다.
