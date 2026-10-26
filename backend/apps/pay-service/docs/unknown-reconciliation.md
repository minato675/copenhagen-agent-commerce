# UNKNOWN 대사 구현 기준

이슈: S15P21A207-200. Pay API 명세 v1.5의 8.3절을 구현한다.
대사는 Pay Worker 내부 작업이며 HTTP Controller를 추가하지 않는다.

## 기존 구현 확인

- `PaymentExecutionSettlementService`가 응답 불명 결제를 UNKNOWN으로 저장하고 Job을 생성한다.
- `JdbcPaymentExecutionRepository`는 기존 Payment·CHARGE Attempt·PG Transaction을 함께 조회한다.
- `PaymentLifecycleService`가 결제 성공·실패에 맞춰 승인 세션을 종료한다.
- V2·V3 migration에 Attempt당 CHARGE 1개, Attempt·사유별 Job 1개, Payment당 Receipt 1개 제약이 있다.
- Job 선점 SQL은 존재하지만 최대 횟수 제한과 이전 Worker의 완료를 막는 소유권 검증이 필요하다.
- PG 조회 포트, Mock 조회 시나리오, UNKNOWN 결과 반영 및 예약 Worker는 추가해야 한다.

## 처리 경계

1. 짧은 트랜잭션에서 실행 가능한 Job을 `FOR UPDATE SKIP LOCKED`로 선점한다.
2. 기존 Payment가 UNKNOWN인지 검사하고 기존 거래 식별자로 PG 상태만 조회한다.
3. 별도 트랜잭션에서 Job 소유권과 Payment 상태를 다시 검사하고 결과를 반영한다.

PG 네트워크 호출 중 DB 잠금을 유지하지 않는다. 선점 횟수와 lease 만료 시각을 소유권으로
사용해 회수 이전 Worker의 늦은 결과를 차단한다. 서로 다른 사유의 Job도 같은 Payment에 대해
동시에 조회하지 않도록 선점을 직렬화한다.

## 상태 및 재시도

- 성공: Payment·Attempt·PG Transaction SUCCESS, 승인 세션 COMPLETED, Receipt 1회 발급.
- 명확한 거절: 세 상태 FAILED, 승인 세션 INVALIDATED, Receipt 미발급.
- 두 확정 결과 모두 대사 Job은 SUCCEEDED로 종료한다. Job FAILED는 조회 미확정을 뜻한다.
- 미확정: Payment UNKNOWN 유지, PG 조회 시각 갱신, FAILED로 다음 조회 예약.
- 조회는 최대 5회이며 5번째에도 미확정이면 EXHAUSTED로 종료한다.
- 마지막 조회 중 Worker가 중단되어도 lease 만료 후 EXHAUSTED로 정리하여 6번째 조회를 막는다.
- 이미 확정된 Payment의 남은 Job은 PG를 조회하지 않고 CANCELLED로 종료한다.
- 새 Payment·CHARGE Attempt·PG 청구, 자동 카드 변경, Outbox 및 실시간 이벤트는 추가하지 않는다.
- 조회 오류는 고정된 코드로만 저장하며 외부 응답 원문이나 예외 메시지를 로그에 남기지 않는다.

## 커밋 및 검증 단위

1. 현재 구현과 설계 기준 문서화
2. PG 상태 조회 Adapter 및 Mock 계약
3. Job 선점·lease 소유권·최대 5회 재시도
4. 대사 결과 원자 반영 및 영수증 멱등성
5. 예약 Worker 연결 및 운영 설정
6. Acceptance Criteria 회귀·PostgreSQL 동시성·스모크 검증

검증은 관련 모듈 테스트에서 시작해 전체 Backend Checkstyle·build로 확장한다.
실제 PostgreSQL에서 Flyway와 잠금·중복 제약을 검증하고 Docker Compose 스모크를 실행한다.
환경상 실행하지 못한 검증은 통과로 기록하지 않는다.
