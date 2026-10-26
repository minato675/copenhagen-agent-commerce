# UAE E-Commerce Fraud Dataset 설명

## 1. 데이터 개요

| 항목 | 내용 |
|---|---|
| 원본 파일 | `uae_ecom_fraud_100k.csv` |
| 데이터 성격 | UAE 전자상거래 결제 Fraud Detection용 Synthetic Dataset |
| 행 수 | 100,000건 |
| 원본 컬럼 수 | 36개 |
| 고유 사용자 수 | 28,937명 |
| 고유 거래 수 | 100,000건 |
| 거래 기간 | 2024-01-01 00:02:03 UTC ~ 2024-12-31 23:54:53 UTC |
| 통화 | AED 100% |
| 결측치 | 0건 |
| 중복 행 | 0건 |

이 데이터는 UAE 결제 환경을 모사한 사기거래 데이터다. 한국 결제 환경에 바로 적용하는 실거래 데이터가 아니며, AI Agent 결제 FDS Prototype의 전처리·Feature Engineering 검증을 위한 입력으로 사용한다.

## 2. Target 분포

Target 컬럼은 `is_fraud`다.

| 값 | 의미 | 건수 | 비율 |
|---:|---|---:|---:|
| 0 | 정상 거래 | 91,789 | 91.789% |
| 1 | Fraud 거래 | 8,211 | 8.211% |

모델 학습 시 Fraud 비율이 약 8.2%인 불균형 분류 문제가 된다. Accuracy만으로 모델을 평가하지 말고 PR-AUC, Recall, Precision, F1, 비용 기반 지표 등을 함께 확인해야 한다.

## 3. 원본 컬럼 설명

### 거래·금액

| 컬럼 | 설명 | 처리 방향 |
|---|---|---|
| `transaction_id` | 거래 식별자 | Feature에서 제거 |
| `amount_aed` | 거래 금액(AED) | 원본 보존, `amount_krw` 생성 |
| `currency` | 거래 통화 | 현재 데이터는 모두 AED이며 모델 입력에서는 제외 |
| `items_count` | 상품 수량 | 숫자형 Feature로 사용 |
| `avg_item_price` | 평균 상품 가격 | `currency` 기준 AED 금액으로 보고 `avg_item_price_krw` 생성 |
| `payment_method` | 결제수단 | 범주형 One-Hot Feature |
| `merchant_category` | 가맹점/상품 카테고리 | 범주형 One-Hot Feature |

### 사용자·계정·카드

| 컬럼 | 설명 | 처리 방향 |
|---|---|---|
| `user_id` | 사용자 식별자 | 사용자 통계 생성 후 모델 입력에서 제거 |
| `user_prev_chargebacks` | 과거 차지백 횟수 | 숫자형 Feature |
| `user_is_high_risk` | 사용자 고위험 여부 | 0/1 숫자형으로 정규화 |
| `user_account_age_days` | 계정 생성 후 경과일 | 숫자형, `is_fresh_account` 생성 |
| `card_age_days` | 카드 사용 기간 | 숫자형 Feature |
| `card_present` | 카드 실물 제시 여부 | 0/1 Feature |
| `card_country_match` | 카드 국가와 거래 정보의 일치 여부 | 0/1 Feature |
| `bin_country` | 카드 BIN 국가 | UAE 국가명 자체의 의존성을 피하기 위해 모델 입력에서 제외 |

### 시간·거래 속도

| 컬럼 | 설명 | 처리 방향 |
|---|---|---|
| `timestamp_utc` | 거래 발생 시각(UTC) | KST 변환, 시간순 정렬 기준 |
| `local_hour` | 원본 로컬 시간 Feature | 보조 Feature로 보존 |
| `odd_hour` | 원본 비정상 시간대 Indicator | 합성 규칙 가능성을 검토하며 사용 |
| `transactions_last_24h` | 원본 최근 24시간 거래 횟수 | 숫자형 Feature 및 계산값과 비교 |
| `transactions_last_1h` | 원본 최근 1시간 거래 횟수 | 숫자형 Feature 및 계산값과 비교 |

### 접속·배송·주소

| 컬럼 | 설명 | 처리 방향 |
|---|---|---|
| `device_type` | 접속 기기 유형 | 범주형 One-Hot Feature |
| `browser` | 브라우저 유형 | 범주형 One-Hot Feature |
| `ip_address` | 원본 IP 주소 | 직접 식별자이므로 모델 입력에서 제거 |
| `ip_risk_score` | IP 위험 점수 | 결제 시점 획득 가능한 Risk Feature로 유지 |
| `email_domain` | 이메일 도메인 | 원문 이메일이 아니므로 저카디널리티 범주형으로 유지 |
| `shipping_city` | 배송지 도시 | 도시명 자체는 모델 입력에서 제외 |
| `billing_city` | 청구지 도시 | 도시명 자체는 모델 입력에서 제외 |
| `shipping_billing_match` | 배송지·청구지 일치 여부 | 0/1 Feature |

### Label·메타데이터

| 컬럼 | 설명 | 처리 방향 |
|---|---|---|
| `is_fraud` | Fraud 정답 Label | 최종 Dataset에 유지, 0/1 검증 |
| `fraud_flag_ip` | IP 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `fraud_flag_mismatch` | 불일치 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `fraud_flag_velocity` | 거래 속도 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `fraud_flag_new_account` | 신규 계정 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `fraud_flag_prev_cb` | 차지백 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `fraud_flag_odd_hour` | 시간대 기반 합성 Fraud Rule 결과 | Leakage 방지를 위해 제거 |
| `data_source` | 데이터 생성 출처 메타데이터 | 모델 입력에서 제외 |

## 4. 전처리 및 Feature Engineering

### 통화 변환

- `amount_aed` → `amount_krw`
- `avg_item_price` → `avg_item_price_krw`
- 기본 변환 계수: `AED_TO_KRW = 370.0`
- 원본 AED 컬럼은 삭제하지 않고 보존한다.
- 환율 자체를 Fraud 판단 기준으로 사용하지 않고, 사용자 과거 평균 대비 금액 Feature를 함께 사용한다.

### 사용자 행동 기반 Feature

거래를 timestamp 오름차순으로 정렬한 후, 현재 거래보다 이전 행만 사용한다.

- `user_avg_amount_prev`
- `user_median_amount_prev`
- `user_std_amount_prev`
- `user_txn_count_prev`
- `user_avg_amount_30d`
- `user_avg_hour_prev`
- `hour_deviation`

### 금액 이상 Feature

- `log_amount`: `log1p(amount)` 기반 변환
- `amount_ratio`: 현재 금액 / 사용자 이전 평균 금액
- `amount_deviation`: 현재 금액 - 사용자 이전 평균 금액
- `amount_zscore`: 사용자 이전 평균·표준편차 기준 Z-score
- `calculated_avg_item_price`
- `amount_per_item`
- `amount_clipped`: 99.5 percentile 상한을 적용한 보조 컬럼

과거 거래가 없거나 분모가 0인 경우 `amount_ratio`와 `amount_zscore`는 안전한 기본값을 사용한다. 원본 이상치는 삭제하지 않는다.

### Velocity Feature

- 원본: `transactions_last_1h`, `transactions_last_24h`
- 계산: `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc`
- 비교: `transactions_last_1h_diff`, `transactions_last_24h_diff`
- 종합 Indicator: `high_velocity`

원본과 직접 계산한 값은 다음과 같이 차이가 있었다.

| 비교 Feature | 비교 건수 | 평균 절대차 | 최대차 | 불일치 건수 |
|---|---:|---:|---:|---:|
| `transactions_last_1h` vs `txn_count_1h_calc` | 100,000 | 0.35609 | 5 | 29,715 |
| `transactions_last_24h` vs `txn_count_24h_calc` | 100,000 | 1.65857 | 11 | 70,815 |

### 시간 Feature

`timestamp_utc`를 UTC 기준으로 파싱한 후 한국시간(UTC+9)으로 변환한다.

- `kst_hour`
- `day_of_week`
- `is_weekend`
- `is_night`

현재 데이터는 시간순으로 정렬되어 있으며, 사용자 통계·Velocity 계산에 미래 거래를 사용하지 않는다.

### 지역·접속 Feature

Dubai, Abu Dhabi 등 도시명 자체를 핵심 Fraud 신호로 사용하지 않는다. 대신 다음과 같은 관계형 Feature를 우선한다.

- `card_country_match`
- `shipping_billing_match`
- `city_match`
- `ip_risk_score`
- `device_type`
- `browser`

## 5. Leakage 및 식별자 처리

다음 컬럼은 정답 생성 규칙 또는 정답에 가까운 정보를 포함할 수 있어 모델 입력에서 제거했다.

```text
fraud_flag_ip
fraud_flag_mismatch
fraud_flag_velocity
fraud_flag_new_account
fraud_flag_prev_cb
fraud_flag_odd_hour
```

또한 컬럼명에 `fraud_flag`, `fraud_score`, `risk_label`, `fraud_reason`이 포함되는 경우 Leakage 가능성을 경고하고 제거한다.

`ip_risk_score`는 컬럼명에 `fraud`가 포함되지 않고 결제 시점에 조회 가능한 서비스 Feature이므로 유지한다.

직접 식별자인 `transaction_id`, `user_id`, `ip_address`는 사용자 통계 계산이 끝난 뒤 최종 모델 입력에서 제거한다. `email_domain`은 원문 이메일이 아닌 도메인 범주이므로 One-Hot Feature로 유지한다.

## 6. 생성된 산출물

| 파일 | 설명 |
|---|---|
| `data/processed/fraud_processed.csv` | Leakage 컬럼 제거 후 원본·파생 Feature를 함께 보존한 Dataset |
| `data/processed/fraud_model_features.csv` | 식별자·원시 위치/시간 컬럼 제거 및 범주형 인코딩을 완료한 모델 입력 Dataset |
| `data/processed/feature_summary.csv` | Feature별 dtype, 결측치, unique 수, min/max/mean/median 요약 |
| `data/reports/preprocessing_report.md` | 실행 결과, 경고, Leakage·식별자 제거 내역 및 제한사항 |

현재 생성 결과는 다음과 같다.

- `fraud_processed.csv`: 100,000행 × 59열
- `fraud_model_features.csv`: 100,000행 × 78열(Feature 77개 + `is_fraud`)
- `feature_summary.csv`: 78행

## 7. 실행 방법

프로젝트 루트에서 실행한다.

```bash
python -m src.preprocessing.run_preprocessing --project-root .
```

CSV는 우선 `data/raw/*.csv`에서 탐색하고, 해당 디렉터리가 없으면 프로젝트 루트의 CSV를 fallback으로 탐색한다. `is_fraud`가 없는 CSV에서는 라벨을 추정하지 않고 report-only 상태로 중단한다.

## 8. 한계 및 주의사항

> 본 데이터는 UAE 전자상거래 환경을 기반으로 생성된 Synthetic Fraud Dataset이며, 한국 실제 금융거래 데이터가 아니다. 따라서 본 전처리는 AI Agent 기반 결제 FDS Prototype 검증을 위한 Domain Adaptation이며 실제 금융기관 Production FDS를 의미하지 않는다.

- 본 데이터의 Fraud Label과 일부 Feature는 합성 데이터 생성 규칙의 영향을 받을 수 있다.
- 명시적인 `fraud_flag_*` 컬럼을 제거했더라도 `odd_hour`, 원본 Velocity 등 합성 과정과 연관된 Feature가 남아 있을 수 있으므로 Feature 중요도와 시간 기반 검증이 필요하다.
- 실제 모델 학습에서는 과거 데이터를 Train, 미래 데이터를 Validation/Test로 분리해야 한다.
- `AED_TO_KRW=370.0`은 Prototype 기본값이다. 운영 환경에서는 거래 시점 환율 또는 내부 환율 정책으로 교체해야 한다.
- 현재 실행 환경에는 `scikit-learn`과 `joblib`이 없어 categorical encoding은 `pandas.get_dummies` fallback으로 생성됐다. 운영 추론용 encoder 재사용이 필요하면 해당 라이브러리를 설치하고 encoder를 함께 관리해야 한다.
