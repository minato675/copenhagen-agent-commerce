# 공개 Fraud 데이터셋 비교 및 프로젝트 적합성 분석

작성일: 2026-09-08

## 1. 조사 목적

Payment MCP에서 사용할 이상거래 탐지 모델의 학습 데이터 후보를 비교하고, 최종 Pay ERD에서 조회할 수 있는 데이터와 공통으로 사용할 수 있는 Feature를 선별한다.

비교 대상은 공개 결제·전자상거래 Fraud 데이터셋 4종이다.

- Credit Card Fraud Detection(ULB)
- Fraud ecommerce
- IEEE-CIS Fraud Detection
- PaySim

실제 학습 산출물은 공개 원본을 그대로 사용하는 것이 아니라, 프로젝트에서 확보 가능한 데이터와 동일한 의미를 갖도록 Feature를 재구성한다. 현재 생성된 최종 학습 후보는 [`fraud_model_features_erd_common.csv`](./fraud_model_features_erd_common.csv)이다.

## 2. 비교 기준

- Rows: 거래 레코드 수
- Input Features: Target을 제외한 입력 컬럼 수
- Fraud Ratio: `Fraud Count / Rows * 100`
- Feature 활용 비율: 각 공개 데이터셋의 원본 입력 컬럼 중 최종 Pay 모델의 입력을 직접 제공하거나 결정적으로 파생할 수 있는 컬럼의 비율
- Feature 활용 비율은 모델 성능이 아니라 ERD와의 구조적 호환성을 나타낸다.

## 3. 데이터셋 요약

| Dataset | Rows | Input Features | Target | Fraud Count | Fraud Ratio | License / 이용 조건 | 프로젝트 활용 비율 |
|---|---:|---:|---|---:|---:|---|---:|
| Credit Card Fraud Detection(ULB) | 284,807 | 30 | `Class` | 492 | 0.17275% | Kaggle: Database Open Database / Contents Database Contents. FDB는 ODC DbCL 1.0을 연결함 | 2/30 = 6.67% |
| Fraud ecommerce | 151,112 | 10 | `class` | 14,151 | 9.36458% | Amazon FDB source record 기준 명시 라이선스 없음 | 4/10 = 40.00% |
| IEEE-CIS Fraud Detection | 590,540 | 433 | `isFraud` | 20,663 | 3.49900% | Competition Data는 비상업적 이용만 허용되며 외부 재배포 금지 | 2/433 = 0.46% |
| PaySim | 6,362,620 | 10 | `isFraud` | 8,213 | 0.12908% | Kaggle metadata 기준 CC BY-SA 4.0. 출처 표시 및 ShareAlike 조건 확인 필요 | 3/10 = 30.00% |

IEEE-CIS Feature 수는 `train_transaction`과 `train_identity`를 `TransactionID`로 결합한 기준이다. Target `isFraud`를 제외한 433개 필드이며, 그중 `TransactionID`는 식별자이므로 실제 예측 입력에서는 제외해야 한다.

## 4. Dataset Schema 분석

### 4.1 Credit Card Fraud Detection(ULB)

주요 컬럼은 `Time`, `V1`~`V28`, `Amount`, `Class`이다. `V1`~`V28`은 개인정보 보호를 위해 PCA 변환된 익명 수치 Feature다. Kaggle 설명에 따르면 2일 동안 발생한 284,807건의 거래 중 492건이 Fraud이며, Fraud 비율은 약 0.172%다.

프로젝트와 직접 연결할 수 있는 값은 금액과 상대적인 거래 시간 정도다. 사용자 ID, 계정 생성일, 결제수단, 승인 세션, 결제 시도 이력이 없어 사용자별 평균 금액이나 ERD 기반 반복 결제 이력을 재현하기 어렵다.

판단: 극심한 Class Imbalance 실험용 기준 데이터로는 유용하지만 Payment MCP의 최종 학습 데이터로는 부적합하다.

### 4.2 Fraud ecommerce

로컬 보유 원본 `Fraud_Data.csv` 기준 컬럼은 다음과 같다.

```text
user_id, signup_time, purchase_time, purchase_value,
device_id, source, browser, sex, age, ip_address, class
```

공개 원본은 약 15만 건의 전자상거래 거래이며, Amazon Fraud Dataset Benchmark는 `user_id`, 가입·구매 시각, 구매 금액, 디바이스, 브라우저, IP 등을 주요 변수로 설명한다. 로컬 파일의 전체 기준으로 151,112건 중 14,151건이 Fraud다.

`user_id`, `signup_time`, `purchase_time`, `purchase_value`는 각각 Pay ERD의 사용자 식별, 계정 생성일, 결제 시각, 승인 금액과 의미를 맞출 수 있다. 따라서 금액 편차, 계정 연령, 거래 시간, 사용자별 거래 이력 Feature를 구성하기 가장 쉽다.

단, FDB source record는 명시적인 Dataset License를 `None`으로 기록하고 있다. 내부 학습과 실험에 사용할 수 있는지와 별개로, 데이터 재배포나 상업적 사용은 권리 확인 전제 없이는 진행하지 않는다.

판단: 공개 후보 중 프로젝트 Feature와 가장 높은 구조적 유사성을 가지므로 공개 기준 데이터로 선정한다.

### 4.3 IEEE-CIS Fraud Detection

`train_transaction`과 `train_identity`를 결합하는 카드 미사용 결제(CNP) 데이터셋이다. 주요 Feature 그룹은 금액·거래 시간, 상품, 카드, 주소, 이메일, 디바이스 정보와 익명화된 `V`, `C`, `D`, `M` 계열 컬럼이다. Target은 `isFraud`다.

Vesta가 제공한 실제 전자상거래 거래 기반 데이터로 데이터 규모와 Feature 다양성은 가장 크다. 그러나 Feature의 의미가 익명화되어 있고, Pay ERD의 `userId`, `authorizationSession`, `paymentMethod`와 직접 대응하지 않는다. 또한 Kaggle Competition Rules는 Competition Data의 비상업적 이용과 외부 재배포 제한을 명시한다.

판단: 대규모 CNP Fraud 벤치마크로는 적합하지만 라이선스 조건과 ERD 의미 차이 때문에 최종 학습 Dataset으로 선정하지 않는다.

### 4.4 PaySim

PaySim은 실제 금융 로그를 바탕으로 모바일 머니 거래를 시뮬레이션한 데이터셋이다. 30일, 744개 시간 단위의 거래를 포함하며 주요 컬럼은 다음과 같다.

```text
step, type, amount, nameOrig,
oldbalanceOrg, newbalanceOrig, nameDest,
oldbalanceDest, newbalanceDest, isFlaggedFraud, isFraud
```

`amount`, `step`, `nameOrig`는 각각 결제 금액, 시간, 사용자 또는 송신자 식별자의 대체값으로 활용할 수 있다. 그러나 모바일 송금·입출금 중심 데이터이므로 Payment MCP의 Merchant Checkout, 승인 세션, 결제 증표, 결제수단과는 도메인이 다르다.

판단: 시간 흐름과 사용자별 거래량을 포함한 Synthetic 보완 데이터로는 유용하지만, Merchant Payment 모델의 주 Dataset으로는 부적합하다.

## 5. Pay ERD와 Feature Mapping

최종 ERD의 핵심 데이터와 모델 Feature의 연결은 다음과 같다.

| 모델 Feature 영역 | 학습 Feature | ERD 또는 서비스 원천 | 파생 방식 | 적용 시나리오 |
|---|---|---|---|---|
| 금액 | `amount_krw`, `log_amount`, `amount_clipped` | `authorization_sessions.amount_minor`, `currency` | 통화 정규화 후 변환 | F01 |
| 사용자 금액 이력 | `user_avg_amount_prev`, `user_median_amount_prev`, `user_std_amount_prev`, `user_avg_amount_30d`, `user_txn_count_prev` | `authorization_sessions.user_id`, `created_at`, `amount_minor` | 현재 거래 이전 이력만 집계 | F01 |
| 금액 편차 | `amount_ratio`, `amount_deviation`, `amount_zscore` | 현재 승인 세션과 이전 사용자 이력 | 평균·표준편차 기준 계산 | F01, F04 |
| 거래 빈도 | `txn_count_10m`, `txn_count_1h_calc`, `txn_count_24h_calc` | `authorization_sessions.user_id`, `created_at` | 사용자별 시간 윈도우 집계 | F02 |
| 시간대 | `kst_hour`, `day_of_week`, `is_weekend`, `is_night`, `user_avg_hour_prev`, `hour_deviation` | `authorization_sessions.created_at` | KST 변환 및 사용자별 시간대 비교 | F03 |
| 계정 연령 | `user_account_age_days`, `is_fresh_account` | 별도 계정 서비스의 `accountCreatedAt` + ERD `user_id`·`created_at` | 계정 생성 후 경과일 계산 | F04 |
| 결제수단 | `payment_method_*` | `selected_payment_method_id` → `payment_methods.provider` | Provider One-hot 변환 | F16 |

다음 데이터는 현재 최종 Feature에 포함하지 않는다.

| 제외 영역 | 제외 이유 |
|---|---|
| IP 위험도·IP 국가 | 최종 Pay ERD에 IP 원천 또는 Reputation 정보가 없음 |
| 카드 국가·카드 연령·Card Present | `payment_methods`에 모델과 동일한 의미의 데이터가 없음 |
| Chargeback·고위험 사용자 | ERD에 이력 테이블 또는 상태 필드가 없음 |
| 브라우저·디바이스·이메일 도메인 | 최종 ERD의 모델 입력 원천으로 확정되지 않음 |
| 상품 수량·상품당 금액 | `canonical_checkout_json` 내부 Schema가 아직 확정되지 않음 |
| 배송지·청구지 비교 | JSON 내부 주소 필드와 정규화 규칙이 아직 확정되지 않음 |

## 6. 프로젝트 활용 가능 Feature 비율

비율은 다음 식으로 계산했다.

```text
프로젝트 활용 비율
= 최종 Pay 모델에 직접 매핑 또는 결정적 파생이 가능한 원본 입력 컬럼 수
  / Dataset의 전체 입력 컬럼 수
```

| Dataset | 활용 가능한 원본 컬럼 | 전체 입력 컬럼 | 활용 비율 | 해석 |
|---|---:|---:|---:|---|
| Credit Card Fraud Detection(ULB) | 2 | 30 | 6.67% | Amount와 상대 시간만 활용 가능 |
| Fraud ecommerce | 4 | 10 | 40.00% | 사용자·가입일·결제시각·금액이 모두 존재 |
| IEEE-CIS | 2 | 433 | 0.46% | 금액·상대 시간 외에는 ERD 의미와 불일치 |
| PaySim | 3 | 10 | 30.00% | 금액·시간·송신자 식별자를 활용 가능 |

현재 보유한 `fraud_model_features.csv`는 이미 파생 Feature가 생성된 77개 입력 컬럼을 포함한다. 이 중 최종 ERD와 공통으로 사용할 27개 입력을 남겨 [`fraud_model_features_erd_common.csv`](./fraud_model_features_erd_common.csv)를 만들었으며, 원본 입력 기준 보존 비율은 `27/77 = 35.06%`다.

## 7. Synthetic Data 보완 설계

공개 Dataset은 행동 기반 Fraud 학습에는 사용할 수 있지만, Pay ERD의 보안 흐름과 승인 증표 공격을 포함하지 않는다. 따라서 아래 ERD 전용 시나리오는 별도 Synthetic Data로 보완한다.

| 시나리오 | ERD 데이터 | Synthetic 조작 예시 | 권장 사용 |
|---|---|---|---|
| F11 승인 금액 변경 | `authorization_sessions.amount_minor`, `payment_mandates.amount_minor`, `payments.amount_minor` | 승인 후 결제 금액을 변경 | Rule 평가셋 |
| F12 Checkout 변경 | `checkout_digest`, `canonical_checkout_json`, `request_hash` | 상품·금액 변경 후 Digest 불일치 | Rule 평가셋 |
| F13 Merchant 변경 | `merchant_id`, `merchant_account_id`, token binding | 다른 Merchant에서 증표 사용 | Rule 평가셋 |
| F14 중복·재사용 결제 | `idempotency_key`, `payment_attempts`, `consumed_at` | 동일 요청 또는 Credential 재사용 | Rule 평가셋 |
| F17 만료 증표 사용 | `expires_at`, `status`, `consumed_at` | 만료·소비된 Mandate/Token 사용 | Rule 평가셋 |
| F19 인증 우회 | `required_action`, `approved_at`, biometric challenge 상태 | 인증 완료 전 결제 실행 | Rule 평가셋 |

ERD 전용 Synthetic 레코드를 AI 학습 데이터에 무분별하게 섞으면 모델이 단순한 Rule 위반만 학습할 수 있다. 따라서 ERD 공격 시나리오는 우선 별도 검증셋과 시연용 데이터로 관리하고, AI 학습에 추가할 때는 실제 사용자 행동 분포와 함께 재검증한다.

## 8. 결론

공개 Dataset 중에서는 **Fraud ecommerce**가 프로젝트와 가장 높은 Feature 구조적 유사성을 가진다. 그러나 실제 Payment MCP 모델은 공개 원본을 직접 서비스 입력으로 사용하지 않고, ERD의 승인 세션·사용자 이력·결제수단 데이터를 이용해 동일한 의미의 Feature를 계산해야 한다.

따라서 최종 구성은 다음과 같이 결정한다.

1. 공개 기준 Dataset: `Fraud_Data.csv` 기반 Fraud ecommerce
2. 실제 AI 학습 Dataset: `fraud_model_features_erd_common.csv`
3. ERD 보안 공격: 별도 Synthetic Rule 평가셋으로 보완
4. 계정 생성 정보 수신 후 `user_account_age_days`, `is_fresh_account` 재계산

### 참고 자료

- [Kaggle - Credit Card Fraud Detection](https://www.kaggle.com/mlg-ulb/creditcardfraud/home)
- [Open Data Commons - Database Contents License 1.0](https://opendatacommons.org/licenses/dbcl/1-0/)
- [Kaggle - Fraud ecommerce](https://www.kaggle.com/datasets/vbinh002/fraud-ecommerce)
- [Amazon Fraud Dataset Benchmark README](https://github.com/amazon-science/fraud-dataset-benchmark/blob/main/README.md)
- [Kaggle - IEEE-CIS Fraud Detection Data](https://www.kaggle.com/competitions/ieee-fraud-detection/data)
- [Kaggle - IEEE-CIS Competition Rules](https://www.kaggle.com/competitions/ieee-fraud-detection/rules)
- [Kaggle - PaySim Metadata](https://www.kaggle.com/datasets/ealaxi/paysim1/metadata)
- [PaySim dataset description and schema](https://pmc.ncbi.nlm.nih.gov/articles/PMC7956727/)
- [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)