# Cold Feature 입력과 변환

`transform_features(FeatureTransformInput)`은 BE가 제공한 현재 구매와 과거 사건을 금액·빈도·시간대 값으로 바꾸는 순수 함수다. 시계, 환경변수, DB, API, 파일을 읽지 않는다. 별도 CLI만 명시한 JSON 파일을 읽는다. 점수 계산·평균/분포 학습·가중치·위험등급·승인 실행은 수행하지 않는다.

## BE 제공 데이터

| 입력 | 의미와 요구 조건 |
|---|---|
| `evaluation` | 기존 `BehaviorEvaluationRequest`: 사용자·평가·구매 요청·주문 스냅샷 ID/버전/해시·의도 버전, 평가 시각, Feature 명세와 요청 목록, 선택적 프로필 |
| `evaluation.request.order.total` | 현재 주문 스냅샷의 최종 금액 `amount_minor`와 ISO 통화. 필수이며 판매처 금액을 다시 합산하지 않음 |
| `current_occurred_at` | 현재 구매 요청이 발생한 시각. 없으면 시간대 Feature는 미수집. 재평가 시각으로 대체하지 않음 |
| `current_available_at` | 해당 현재 사건 정보가 평가 시스템에서 이용 가능해진 시각. 발생 시각과 함께 제공 |
| `current_attempt_id` | 필수 필드. 현재 결제 시도가 있다면 그 ID. 아직 현재 시도가 없음을 BE가 확인한 경우에만 명시적으로 `null`. 식별자를 모르면 입력을 만들지 않음. 동일 구매의 과거 다른 시도는 유지 |
| `history.as_of` | 제공된 이력의 정보 가용 기준 시각. 평가 시각보다 늦으면 거부 |
| `history.purchases`, `history.attempts` | 스트림별 수집 상태와 완전한 조회 범위 `complete_from`, `complete_until` |
| `history.events` | 개별 원천 사건. 사건 ID·종류·사용자·구매 요청·선택적 주문/시도 ID·발생/가용 시각·통화 |

시각은 시간대가 있는 ISO 문자열 또는 datetime이다. epoch 숫자·시간대 없는 시각은 받지 않는다. 현재 사건은 `occurred_at <= available_at <= evaluated_at`이어야 한다. 현재 주문도 평가 당시 제공 가능한 버전을 BE가 선택해야 하며, 계약이 DB의 실제 스냅샷 이력을 인증하지는 않는다.

`PURCHASE_REQUESTED`는 구매 요청이 처음 시작된 사실이며 결제 완료 건수를 뜻하지 않는다. 여러 판매처로 복제되어도 원래 구매 요청 ID·발생 시각·통화가 같아야 한다. `PAYMENT_ATTEMPTED`는 개별 결제 시도의 시작 사실이며 `attempt_id`가 필수다. 시도 ID는 구매 요청 안에서 유일해야 하며, 판매처별 실제 시도는 각각의 ID를 가진다.

결제 성공·실패, 취소·환불, 사기·차지백 확정 같은 후속 결과는 별도의 사건 ID·관련 구매/주문/시도 ID·발생 시각·정보 가용 시각·출처와 함께 BE에서 관리해야 한다. 후속 상태로 시작 사건을 덮어쓰거나 최종 라벨을 현재 변환 입력에 넣지 않는다. 현재 최소 계약은 시작 사실만 받으며 알 수 없는 추가 필드를 거부한다. 프로필의 `as_of`와 생성 시각도 별도 계약을 유지하며, 관측 표본 수를 평균·분산으로 사용하지 않는다.

BE가 담당하는 범위는 원천 수집·조회·저장·사용자 인증·정확한 시간 기준과 사건 정체성 보장이다. 이 문서는 필요한 데이터 의미를 정의하며 Core/Pay 테이블이나 API 구현을 변경하지 않는다.

## Feature 정의

명세 버전은 `cold-features-v1`이다. 이 버전에서 아래 ID만 요청할 수 있다. 요청한 모든 Feature는 값 또는 미산출 상태로 반환하며 출력 순서는 아래 순서로 고정된다.

| Feature ID | 계산·원천 | 단위/범위 | 이상 판단 시 해석 |
|---|---|---|---|
| `amount_minor` | 현재 `order.total.amount_minor` 그대로 | 통화별 최소 단위, 0 이상 정수 | 개인/집단 금액 기준과 편차 비교용. 큰 값만으로 이상 확정 안 함 |
| `purchase_count_10m` / `1h` / `24h` | 각 시간 창의 서로 다른 과거 구매 요청 수 | 동일 통화의 구매 요청 건수 | 단시간 빈도 상승 비교용 |
| `attempt_count_10m` / `1h` / `24h` | 각 시간 창의 서로 다른 `(purchase_request_id, attempt_id)` 수 | 동일 통화의 시도 건수 | 재시도·시도 집중도 비교용. 실패 횟수와 다름 |
| `kst_hour` | 현재 발생 시각을 고정 UTC+09:00로 변환한 hour | 0~23 | 순환형 값. 23시와 0시는 인접하며 단순 대소가 위험 방향이 아님 |
| `kst_day_of_week` | 같은 KST 시각의 weekday | 월요일 0~일요일 6 | 요일 패턴 비교용, 순환형 값 |

빈도 창은 `[evaluated_at - window, evaluated_at)`이며 10분=600초, 1시간=3600초, 24시간=86400초다. 평가 시각은 호출자가 고정한다. 현재 구매 요청은 과거 구매 수에서 전부 제외한다. 현재 시도가 있으면 해당 시도를 제외하되 동일 구매의 **이전 다른 시도**는 시도 수에 포함한다. 판매처 주문 개수를 구매 수로 세지 않으며, 동일 통화의 개별 판매처 시도는 서로 다른 실제 시도라면 각각 센다.

금액은 KRW 1000=1,000원, USD 1000=10달러처럼 입력 최소 단위를 유지한다. 환산·반올림·통화 혼합 합산은 없다. 빈도도 현재 통화와 같은 사건만 센다. 다른 통화 사건은 그 통화 평가에서 사용할 수 있으며 통화 전체 빈도는 이 명세의 범위가 아니다. `amount_krw`, 로그/clip/비율/z-score, `is_night`와 기존 CSV의 `txn_count_*`에 자동 대응시키지 않는다. 특히 기존 CSV는 구매·시도 구분과 시간 창 재현 근거를 별도로 확인해야 한다.

## 시간 누출과 중복

과거 사건을 사용하려면 `occurred_at < evaluated_at`이면서 `available_at <= history.as_of <= evaluated_at`이어야 한다. 같은 시각의 현재 사건과 미래 사건, 과거에 발생했지만 나중에 알려진 사건은 제외한다. 가용 시각 이후 자료는 중복 비교에도 사용하지 않아 사후 자료의 충돌이 과거 결과를 바꾸지 않는다.

정보 가용 조건을 만족하는 동일 `event_id`의 완전히 같은 재전송은 한 번만 센다. 같은 사건 ID의 내용이 다르면 거부한다. 서로 다른 사건 ID라도 같은 논리적 구매/시도라면 한 번만 세고, 발생 시각·통화 또는 시도의 주문 관계가 충돌하면 거부한다. 첫 번째 배열 항목을 임의로 채택하지 않으므로 순서 변경에도 결과가 같다. 다른 사용자 이력이 포함되면 입력 전체를 거부한다.

알려진 현재 구매 요청의 시작 사건이 이력에도 포함되면 현재 입력과 통화 및 제공된 최초 발생 시각이 같아야 한다. 과거 구매 집계에서 제외되는 사건이어도 서로 모순된 현재 시각을 시간대 계산에 사용하지 않는다.

## 미수집과 정상 0 구분

| 반환 상태 | 의미 |
|---|---|
| `AVAILABLE` | 계산 가능한 값. 0원·0건·0시는 실제 값 |
| `NOT_COLLECTED` | 이력/스트림/현재 발생 시각이 제공되지 않음 |
| `INSUFFICIENT_HISTORY` | 수집됐지만 요청 창 전체를 덮는 완전한 이력이 없음. 부분 건수를 전체 건수처럼 반환하지 않음 |
| `NOT_APPLICABLE` | BE가 해당 사건 스트림 자체가 적용되지 않는다고 명시. 수집 실패나 빈 이력을 이 상태로 표시하지 않음 |

미산출 상태의 `value`는 `None`이다. 해당 상태에 0을 넣으면 거부한다. 현재 구매의 금액·통화는 기존 구매 계약상 필수이므로 누락은 입력 오류다.

스트림이 `COLLECTED`여도 완전한 범위가 `[평가 시각-창, 평가 시각)`을 모두 덮어야 해당 빈도가 계산된다. 완전 범위 끝은 `history.as_of`를 넘을 수 없다. 따라서 오래된 이력 스냅샷은 현재 평가의 완전한 빈도로 사용할 수 없다. 빈 이벤트 목록과 완전한 범위를 함께 제공하면 0건이며, 조회하지 않은 목록을 빈 배열로 대신해서는 안 된다. 수집 완전성은 BE의 선언이며 이 함수가 누락된 원천 사건을 탐지하는 것은 아니다.

`AVAILABLE`은 원시 값 계산 완료이며 기존 이상 점수의 `SCORED`와 다르다. 후속 계산기가 기준을 적용해야 이상 점수가 생긴다. `RawFeatureVector.validate_for_request(request)`로 별도 역직렬화 결과의 대상·시각·요청 Feature 집합·명세·통화를 대조한다.

## 직접 검증

가상환경을 활성화한 Git Bash에서 **ai 폴더 기준**으로 실행한다. 현재 `ai/tests`라면 먼저 `cd ..` 한다.

```bash
python -m pytest tests/test_feature_transform.py -v --tb=short
python -m copenhagen_fds.feature_cli examples/cold_feature_input.json
```

예제는 고정된 합성 구매·시도만 포함한다. 금액 10,000 KRW, 구매 수는 각 창 1건, 시도 수는 각 창 2건, KST 0시·월요일(0)이 나온다. 동일 구매의 판매처 복제와 재전송은 구매 수를 늘리지 않고, 현재 구매의 과거 시도는 시도 수에 포함한다. 예제의 `history`를 `null`로 바꾸면 빈도 값은 0이 아니라 `NOT_COLLECTED`와 `null`이 된다.

CSV 점검은 [데이터 적합성 문서](dataset-suitability.md)의 명령과 집계 기준을 사용한다. 위 테스트와 예제는 외부 API·실제 `.env`·개인 데이터·사기 탐지 성능 검증을 사용하지 않는다.

완료 기준별로 나누어 확인하려면 같은 `ai` 위치에서 다음 명령을 실행한다. 일부 테스트는 여러 기준을 함께 검증하므로 선택된 개수를 서로 합산하지 않는다.

1. 금액·빈도·시간대 정상/경계/결측/단위: 84개 선택.

   ```bash
   python -m pytest tests/test_feature_transform.py -v --tb=short -k "amount or unit or missing or time"
   ```

2. 시간 누출·여러 판매처·재시도·중복·대상 연결: 67개 선택.

   ```bash
   python -m pytest tests/test_feature_transform.py -v --tb=short -k "leakage or count or duplicate or binding"
   ```

3. 동일 입력·시각의 재현성과 데이터 점검 도구: 16개 선택. 보유 파일의 사용 범위·부족 항목은 연결된 적합성 문서와 실제 CSV 점검 결과를 함께 확인한다.

   ```bash
   python -m pytest tests/test_data_audit.py tests/test_feature_transform.py -v --tb=short -k "audit or reproducible"
   python -m copenhagen_fds.data_audit data/fraud_model_features_erd_common.csv
   ```

선택한 모든 테스트가 `PASSED`이고 마지막 요약에 실패/오류가 없으면 해당 코드 검증이 통과한 것이다. `deselected`는 명령으로 다른 기준의 테스트를 제외한 것이며 실패가 아니다. 잘못된 입력을 거부하는 사례도 예상한 오류를 확인하면 `PASSED`다. CSV 점검에서 야간 정의 불일치가 나오는 것은 기록된 데이터 문제를 재현한 것이며, 이를 0으로 바꾸거나 학습 적합 판정으로 해석하지 않는다.
