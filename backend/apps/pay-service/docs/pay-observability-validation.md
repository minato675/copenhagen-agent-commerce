# Pay 후속 로그 검증 결과

검증일: 2026-09-17 (KST)

## 대상 버전과 실행 환경

| 항목 | 확인 결과 |
| --- | --- |
| 브랜치 | `feature/S15P21A207-375-pay-fcm` |
| 기준 커밋 | `a306da4` (`develop` 병합 커밋) |
| 테스트 대상 소스 | 현재 로컬 작업 트리. 이번 로깅·검증 변경은 아직 커밋/배포되지 않음 |
| Compose 기본 Pay 이미지 | `copenhagen/pay-service:local` |
| 실제 실행/배포 이미지 | 미검증. Docker CLI가 현재 PC에 설치되어 있지 않음 |
| Pay DB 마이그레이션 | 임베디드 PostgreSQL에서 V11까지 적용 확인 |

로컬 소스 테스트 성공과 배포 환경 반영 여부를 분리했다. 실제 배포 이미지와 운영 설정은
이 문서의 테스트 결과만으로 반영되었다고 판단하지 않는다.

## 자동 검증 결과

실행 명령:

```text
.\gradlew.bat :apps:pay-service:test :apps:pay-service:checkstyleMain :apps:pay-service:checkstyleTest
.\gradlew.bat :apps:pay-service:postgresTest
```

| 구분 | 기대 결과 | 실제 결과 | 증적 |
| --- | --- | --- | --- |
| 정상 Pay API | `X-Request-Id` 응답, 시작/완료 각 1건, method/path/status/duration 기록 | 통과 | `PayApiObservabilityTest` |
| 토큰 누락 | 401, `AUTHENTICATION_REQUIRED`, 완료 로그 1건 | 통과 | `PayAuthenticationObservabilityTest` |
| 토큰 무효·실제 만료 JWT | 401, `INVALID_TOKEN`, 토큰 원문 미출력 | 통과 | `PayAuthenticationObservabilityTest`, `JwtBearerTokenVerifierTest` |
| 권한 거절 | 403, 인증 성공과 권한 거절을 구분 | 통과 | `PayAuthenticationObservabilityTest` |
| 입력 검증 | 400, `INVALID_REQUEST`, 오류/완료 로그 역할 분리 | 통과 | `PayApiObservabilityTest` |
| 대표 PayException | 업무 `errorCode`, HTTP 상태, 완료 로그 연결 | 통과 | `PayApiObservabilityTest` |
| 예상하지 못한 500 | `INTERNAL_ERROR`, 예외 유형만 기록, 원문 미출력 | 통과 | `PayApiObservabilityTest` |
| PG 성공·거절·HTTP 오류 | 상태·허용된 PG 코드·처리 시간 기록, 자유 형식 응답 코드는 안전한 분류로 치환 | 통과 | `PgMockPaymentGatewayTest` |
| PG 타임아웃 | `PG_TIMEOUT`, UNKNOWN 유지, 재호출 0건 | 통과 | `PgMockPaymentGatewayTest` |
| Merchant 호출 | 합의된 `X-Request-Id`가 Stub에 전달 | 통과 | `HttpMerchantPublicKeyClientTest` |
| FCM 성공·Firebase 오류·비활성화 | `pushId`, Firebase 오류 코드, FID 해시, 처리 시간 기록 | 통과 | `FirebaseNotificationSenderTest` |
| 알림 재시도·무효 FID | 시도 횟수·실패 코드·RETRY/SENT 결과·작업 ID 기록 | 통과 | `NotificationDispatchObservabilityTest` |
| 알림 Worker 실패 | `failureStage`, 예외 유형, Worker request ID 기록 | 통과 | `NotificationWorkerTest` |
| 대사 Worker/PG Adapter 실패 | jobId·attemptCount·실패 단계·허용 코드·원문 미출력 | 통과 | `ReconciliationObservabilityTest`, `PayWorkerTest` |
| 동시 요청 MDC | trace·앱/OS/platform 혼입 없음, 요청 종료 후 정리 | 통과 | `PayMdcConcurrencyTest` |
| 대사 requestId 복원 | PG 타임아웃으로 생성된 Job의 원 요청 ID를 Worker가 복원 | 통과 | `ReconciliationPostgresIntegrationTest`, `V11__add_reconciliation_request_id.sql` |
| Outbox requestId/pushId | 원 요청 ID 보존, 재시도 가능한 pushId 유지 | 통과 | `NotificationPostgresIntegrationTest` |
| 합성 민감정보 | PIN·카드·Authorization·Cookie·credential·FCM 원문 미출력 | 통과 | API/Worker/FCM 로그 캡처 검증 |
| Redis 처리 | Pay 코드에 Redis client·연산이 없어 검증 대상 아님 | 해당 없음 | Pay 의존성·소스 검색 |
| 기존 Pay 단위 테스트 | 회귀 없음 | 통과: 219건, 실패 0, 오류 0 | Gradle test 결과 |
| 임베디드 PostgreSQL 통합 테스트 | 상태 전이·재시도·Outbox·마이그레이션 유지 | 통과: 44건, 실패 0, 오류 0 | Gradle postgresTest 결과 |

요청 오류 로그와 요청 완료 로그를 별도로 판정한다. 오류 로그는 실패 분류·`errorCode`를,
완료 로그는 최종 HTTP 상태·처리 시간을 책임진다. 정상/실패 모두 완료 로그가 정확히 한 번
남는지 확인했다. PG 응답의 자유 형식 `responseCode`는 허용 문자·길이를 벗어나면
`PG_RESPONSE_CODE_INVALID`로 치환해 로그와 결제 원장에 원문이 남지 않도록 했다.

## 추적 계약

- Pay는 `X-Request-Id`를 받으면 검증 후 그대로 사용하고, 없거나 유효하지 않으면 UUID를 만든다.
- 같은 값은 응답 헤더와 Pay 로그 MDC의 `requestId`로 출력한다. Jira의 `traceId`는 이 구현에서
  `requestId`와 같은 역할이다.
- 요청 로그의 경로는 query string을 포함하지 않고, 비어 있거나 제어문자를 포함하거나 200자를
  초과하면 `INVALID`로 치환한다.
- Core/Merchant는 실제 서비스 대신 Stub HTTP 서버로 대체해 Pay 단독 검증이 가능하다.
- PG와 Merchant 외부 HTTP 요청에도 같은 `X-Request-Id`를 전달한다.
- 알림 Outbox와 대사 Job에 원 요청 ID를 저장하고 Worker에서 복원한다. 기존 원 요청 ID가 없는
  레거시 Job은 Worker가 생성한 `reconciliation-*` ID를 fallback으로 사용한다.
- Pay 도메인에는 별도 `orderId` 컬럼이 없으므로 현재는 `paymentId`, `approvalId`, `jobId`와
  구매 요청 식별자를 `order` MDC 필드로 연결한다.

## 설정과 배포 확인 결과

`application.yml`의 기본값은 FCM/알림 Worker가 비활성화이고, 대사 Worker는 활성화다. 실제
FCM 테스트에는 다음 Secret/환경 설정이 필요하다.

```text
PAY_FCM_ENABLED=true
PAY_NOTIFICATION_WORKER_ENABLED=true
PAY_FCM_PROJECT_ID=<Firebase project id>
PAY_FCM_SERVICE_ACCOUNT_JSON=<Secret Manager 주입값>
```

현재 `docker-compose.yml`의 Pay 서비스는 DB·PG·인증·대사 설정은 주입하지만 FCM Secret/Worker
환경변수는 포함하지 않는다. 공통 Compose/배포 설정은 인프라 Task의 경계이므로 이 Task에서
임의로 수정하지 않았다. 운영 또는 Compose FCM 검증 전에 S15P21A207-353/376/377 담당자가
해당 설정·Secret 주입·보관 정책을 반영해야 한다.

Docker 환경에서 실행할 Pay 전용 Compose 검증 명령은 다음과 같다.

```bash
bash scripts/pay-observability-smoke.sh
```

이 스크립트는 Pay DB·PG Mock·Pay만 임시 Compose 프로젝트로 기동해 health, 인증 실패,
응답/로그 requestId 연결과 합성 Authorization 비노출을 확인한 뒤 자원을 정리한다.

현재 PC에서는 `docker compose config --quiet`와 `docker --version`을 실행하려 했으나 Docker
CLI 자체가 없어 실제 Compose 기동·컨테이너 이미지·운영 로그 수집은 미검증이다.

## 남은 외부 미검증 항목

- 실제 배포된 Pay 컨테이너가 현재 소스와 동일한지, 이미지 digest와 환경변수 반영 여부
- 실제 Firebase 서버 수락과 모바일 앱의 토큰 생성·알림 권한·포그라운드/백그라운드 수신
- 실제 PG/Nginx/Core/Merchant 연결, Nginx 접근 로그와 Docker 보관·접근 권한
- Redis 연동은 현재 Pay에 존재하지 않으므로, 향후 도입 시 해당 client 경계의 성공·실패 로그를 별도 추가
- 운영 로그 수집기에서 MDC 필드가 보존되는지와 실제 보관 기간

위 항목은 합성 Mock/Pay 단독 테스트의 통과로 대체할 수 없으며, 해당 서비스·인프라 담당자와
공동 환경에서 별도 확인해야 한다.
