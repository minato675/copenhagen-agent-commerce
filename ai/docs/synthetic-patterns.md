# 합성 구매 이력의 생성 근거와 패턴

이 생성기는 금액·구매 및 결제 시도 빈도·한국 시간대/요일의 행동 패턴을 검증하기 위한 독립 시뮬레이터다. 기존 FDS 점수나 임계값을 호출해 정답을 만들지 않는다. 원시 실거래 데이터나 외부 생성기 코드를 복사하지 않았다. 모든 사용자·사건·금액·확인 결과는 가상이며, 구체적인 규모·분포 파라미터·사기 비율은 프로젝트에서 정한 실험 조건이다.

## 참고한 1차 자료

| 자료 | 확인한 내용 | 이번 설계에 반영한 내용 |
|---|---|---|
| [Fraud Detection Handbook의 거래 시뮬레이터](https://fraud-detection-handbook.github.io/fraud-detection-handbook/Chapter_3_GettingStarted/SimulatedDataset.html) | 사용자별 소비 금액과 빈도를 다르게 생성하고 시간에 따른 사기 시나리오를 삽입한다. 전역 고액 규칙은 인위적인 예제라는 한계가 있다. | 개인별 정상 이력을 먼저 만들고, 서로 다른 공격과 정상 예외를 함께 넣는다. |
| [Bahnsen 외, Feature Engineering Strategies for Credit Card Fraud Detection (2016)](https://albahnsen.github.io/files/Feature%20Engineering%20Strategies%20for%20Credit%20Card%20Fraud%20Detection_published.pdf) | 사용자 거래 집계와 순환 시간 변수 기반 특징을 다룬다. | 자정 인접 거래, 야간 사용자, 두 개의 주 구매 시간대를 구성한다. 논문의 실제 성능을 이 구현의 성능으로 인용하지 않는다. |
| [Federal Reserve, CardSim (2025)](https://www.federalreserve.gov/econres/feds/files/2025017pap.pdf) | 사용자별 일일 거래 수, 오른쪽 꼬리를 갖는 금액 분포, 여러 시간대의 구매 피크를 시뮬레이션한다. | Poisson 일일 구매 수, lognormal 금액, 순환 시간대 혼합을 사용한다. 분포 종류는 참고하고 수치 파라미터는 별도로 정했다. |
| [Fraud Detection Handbook의 검증 전략](https://fraud-detection-handbook.github.io/fraud-detection-handbook/Chapter_5_ModelValidationAndSelection/ValidationStrategies.html) | 사기 확인 지연과 시간에 따른 패턴 변화 때문에 시간순 학습·평가를 구분한다. | 발생 시각·이용 가능 시각·후속 확인 시각을 분리하고 시간순 구간을 고정한다. |
| [Stripe의 카드 테스트 설명](https://docs.stripe.com/disputes/prevention/card-testing) | 소액 결제·빈번한 시도·실패 증가를 설명하며 정상적인 과도한 재시도도 유사하게 보일 수 있다. | 소액 연속 시도와 정상 재시도를 함께 넣는다. 카드·IP·기기 기반 탐지까지 검증했다고 해석하지 않는다. |

## 공통 생성 방식

- 기본 장기 스트림은 가상 사용자 120명, 120일, KRW 최소 통화 단위와 `+09:00` 시각이다. 단일 통화 자료이며 환율·다중 통화 검증용은 아니다.
- 사용자마다 금액 중앙값·변동 폭, 일일 구매 빈도, 주 구매 시간대와 시간 분산이 다르다. 대부분의 금액은 lognormal 분포로 양수의 오른쪽 꼬리를 가지며, 동일 금액 반복 유형은 예외적으로 상수다.
- 일일 구매 수는 작은 평균값의 Poisson 분포다. 시간대는 하나 또는 여러 중심 시간 주위에서 생성하고 24시간 주기로 감싼다. 이는 일중·일별 모든 상관관계를 재현하는 금융시장 시뮬레이터가 아니다.
- `routine`, `high_spender`, `night`, `multipeak`, `weekend`, `sparse`, `constant`, `gradual_drift`, `abrupt_drift`, `newcomer`의 10개 유형을 둔다. 사용자 약 20%는 미래 평가 구간에 진입하며 이전 학습 이력이 없다.
- 장기 스트림의 구매별 공격 선택 확률은 0.7%로 설정한다. 실현된 사기 비율은 생성 후 집계하며, 이 수치를 실서비스 사기 비율로 제시하지 않는다.
- 결제 성공·실패와 정상·사기 확인은 서로 다른 사건이다. 정상 거래 일부만 확인 결과를 제공하며, 확인은 1~21일 뒤에 도착할 수 있다. 피드백이 없는 거래를 정상으로 자동 분류하지 않는다.
- 같은 설정과 버전에서 로컬 난수 생성기를 사용한다. 전역 난수, 현재 시각, 네트워크, API 키를 사용하지 않는다. Python 버전과 생성기 파일 해시도 산출물에서 보관해야 한다.

## 고정 도전 시나리오

| 분류 | 시나리오 | 검증 목적 |
|---|---|---|
| 정상 반례 | `normal_high_amount`, `normal_night`, `normal_burst`, `legitimate_retry` | 고액·야간·짧은 구매 간격·재시도만으로 사기를 확정하는 오류 확인 |
| 행동 이상 | `amount_spike`, `frequency_burst`, `off_hour`, `combined`, `low_value_attempt_burst` | 금액·빈도·시간 또는 복합 이탈과 소액 연속 시도의 관측 |
| 탐지 한계 | `pattern_mimic` | 정상 패턴과 비슷한 사기는 현재 행동 Feature만으로 구별하기 어려움을 보존 |
| 패턴 변화 | `gradual_drift`, `abrupt_drift` | 정상적인 습관 변화와 프로필 갱신 지연의 영향 검증 |
| 사건 의미 | `multi_merchant`, `duplicate_delivery` | 구매 전체 금액, 판매처별 사본과 같은 이벤트 재전송 구분 |
| 가용성 | `delayed_event`, `delayed_feedback`, `collection_gap`, `cold_start` | 지연·후속 확인·불완전 수집·개인 이력 부족 처리 |
| 시간 경계 | `midnight_before`, `midnight_after` | 23시와 0시의 순환성과 날짜 경계 처리 |

`challenge`는 기본 집단에 시나리오별 독립 사용자 20명을 추가한다. 개인별 정상 이력을 복제할 때 식별자를 새로 만들고, 원본의 사기 삽입 사례는 제거한다. 서로 다른 시험 공격이 같은 사용자의 직전 10분 이력을 오염시키지 않도록 구성한 진단용 세트다. `config.users`는 기본 집단 수이고 실제 사용자 수는 별도 집계한다. 신규 사용자 시나리오는 과거 이력을 복제하지 않는다.

장기 스트림과 도전 세트는 seed와 식별자가 다르다. 도전 세트의 풍부한 공격과 깨끗한 배경 이력은 시나리오 검증 조건이며, 장기 스트림과 Precision을 직접 비교하지 않는다. 시나리오가 삽입된 마지막 구간과 일반 배경 행도 구분해서 보고한다.

## 원시 데이터와 정답의 분리

`generate(GeneratorConfig(...), suite="stream" 또는 "challenge")`는 다음 목록을 반환한다.

| 목록 | 내용 | 사용 경계 |
|---|---|---|
| `users` | 가상 사용자·통화·가입 시각·수집 시작 시각 | 관측 입력 |
| `purchases` | 구매 전체 금액·통화·발생/가용 시각·주문 참조·판매처별 금액 | 관측 입력, 과거 금액 학습의 원천 |
| `events` | 구매 시작·시도 시작, 사건/구매/시도 ID·발생/가용 시각 | 관측 입력, 중복 재전송을 포함할 수 있음 |
| `outcomes` | 결제 시도 성공·실패와 후속 시각 | 시점상 알려진 결과만 사용, 정상 정답과 별개 |
| `feedback` | 정상·사기 확인과 이용 가능 시각 | 해당 시점 이후의 학습 정책에서만 사용 |
| `coverage_gaps` | 수집기가 공지한 불완전 수집 구간과 공지 시각 | 공지된 시점 이후의 수집 완전성 판단 |
| `truth` | 사기 여부·시나리오·분할·이탈 축·관측 한계 | 평가 전용. 모델 입력에 넣지 않음 |
| `personas` | 사용자 생성 유형과 숨겨진 분포 파라미터 | 평가 전용. 학습된 프로필로 전달하지 않음 |

`behavioral_deviation_axes`는 시뮬레이터가 의도한 변화 축이다. 실제 계산 점수의 정답이나 반드시 100점이어야 한다는 주장이 아니다. 자연적인 꼬리 값 때문에 삽입하지 않은 거래도 행동상 드물 수 있다. `pattern_mimic` 역시 동일 입력 쌍의 수학적 증명이 아니라 정상 분포를 모방한 공격 시나리오다.

기본 `events`의 가용 지연은 0이다. 명시적인 지연 시나리오는 원시 이벤트를 보존하고 가용 시각을 늦추며, 수집기 상태로 불완전 구간을 즉시 공지한다. 공백 시나리오는 해당 구간의 이벤트를 실제로 제거한다. 구매 금액 원장은 별도 스트림이므로 이벤트 수집 공백에도 구매 레코드는 존재할 수 있다.

## 검증과 재사용

```bash
# ai 폴더, 기존 가상환경에서 실행
python -m pytest tests/test_synthetic_generator.py -v --tb=short
```

이 검증은 분포별 차이·정답 분리·사건 관계·시간 관계·재현성과 시나리오 구성을 확인한다. 개인·집단 프로필 학습이나 GPT·FDS 최종 통합을 실행하지 않는다. 시간순 분할과 저장·직접 확인 방법은 산출물 실행 도구의 사용 문서에서 다룬다.
