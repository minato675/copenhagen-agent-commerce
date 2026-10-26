# UAE E-Commerce Fraud Preprocessing Report

**Status:** COMPLETED

## Original Dataset
- Selected input: `uae_ecom_fraud_100k.csv`
- Rows: 100000
- Columns: 36
- Fraud Ratio: 0.082110

## Discovered CSV Schema

### `category_tree.csv`
- Rows: N/A
- Columns: categoryid, parentid
- Dtypes: `{}`
- Missing counts: `{}`
- Duplicate rows: not computed for chunked auxiliary inspection
- Target distribution: unavailable

### `events.csv`
- Rows: N/A
- Columns: timestamp, visitorid, event, itemid, transactionid
- Dtypes: `{}`
- Missing counts: `{}`
- Duplicate rows: not computed for chunked auxiliary inspection
- Target distribution: unavailable

### `item_properties_part1.csv`
- Rows: N/A
- Columns: timestamp, itemid, property, value
- Dtypes: `{}`
- Missing counts: `{}`
- Duplicate rows: not computed for chunked auxiliary inspection
- Target distribution: unavailable

### `item_properties_part2.csv`
- Rows: N/A
- Columns: timestamp, itemid, property, value
- Dtypes: `{}`
- Missing counts: `{}`
- Duplicate rows: not computed for chunked auxiliary inspection
- Target distribution: unavailable

### `uae_ecom_fraud_100k.csv`
- Rows: 100000
- Columns: transaction_id, user_id, timestamp_utc, amount_aed, currency, payment_method, device_type, browser, merchant_category, items_count, avg_item_price, shipping_city, billing_city, shipping_billing_match, ip_address, ip_risk_score, card_present, bin_country, card_age_days, card_country_match, email_domain, user_prev_chargebacks, user_is_high_risk, user_account_age_days, transactions_last_24h, transactions_last_1h, local_hour, odd_hour, is_fraud, fraud_flag_ip, fraud_flag_mismatch, fraud_flag_velocity, fraud_flag_new_account, fraud_flag_prev_cb, fraud_flag_odd_hour, data_source
- Dtypes: `{'transaction_id': 'int64', 'user_id': 'int64', 'timestamp_utc': 'str', 'amount_aed': 'float64', 'currency': 'str', 'payment_method': 'str', 'device_type': 'str', 'browser': 'str', 'merchant_category': 'str', 'items_count': 'int64', 'avg_item_price': 'float64', 'shipping_city': 'str', 'billing_city': 'str', 'shipping_billing_match': 'int64', 'ip_address': 'str', 'ip_risk_score': 'float64', 'card_present': 'int64', 'bin_country': 'str', 'card_age_days': 'int64', 'card_country_match': 'int64', 'email_domain': 'str', 'user_prev_chargebacks': 'int64', 'user_is_high_risk': 'int64', 'user_account_age_days': 'int64', 'transactions_last_24h': 'int64', 'transactions_last_1h': 'int64', 'local_hour': 'int64', 'odd_hour': 'int64', 'is_fraud': 'int64', 'fraud_flag_ip': 'int64', 'fraud_flag_mismatch': 'int64', 'fraud_flag_velocity': 'int64', 'fraud_flag_new_account': 'int64', 'fraud_flag_prev_cb': 'int64', 'fraud_flag_odd_hour': 'int64', 'data_source': 'str'}`
- Missing counts: `{'transaction_id': 0, 'user_id': 0, 'timestamp_utc': 0, 'amount_aed': 0, 'currency': 0, 'payment_method': 0, 'device_type': 0, 'browser': 0, 'merchant_category': 0, 'items_count': 0, 'avg_item_price': 0, 'shipping_city': 0, 'billing_city': 0, 'shipping_billing_match': 0, 'ip_address': 0, 'ip_risk_score': 0, 'card_present': 0, 'bin_country': 0, 'card_age_days': 0, 'card_country_match': 0, 'email_domain': 0, 'user_prev_chargebacks': 0, 'user_is_high_risk': 0, 'user_account_age_days': 0, 'transactions_last_24h': 0, 'transactions_last_1h': 0, 'local_hour': 0, 'odd_hour': 0, 'is_fraud': 0, 'fraud_flag_ip': 0, 'fraud_flag_mismatch': 0, 'fraud_flag_velocity': 0, 'fraud_flag_new_account': 0, 'fraud_flag_prev_cb': 0, 'fraud_flag_odd_hour': 0, 'data_source': 0}`
- Duplicate rows: 0
- `uae_ecom_fraud_100k.csv` target distribution: `{'0': 91789, '1': 8211}`

## Data Leakage Columns Removed

- `fraud_flag_ip` — direct fraud-label generation rule / target proxy
- `fraud_flag_mismatch` — direct fraud-label generation rule / target proxy
- `fraud_flag_velocity` — direct fraud-label generation rule / target proxy
- `fraud_flag_new_account` — column name contains leakage token 'fraudflag'
- `fraud_flag_prev_cb` — column name contains leakage token 'fraudflag'
- `fraud_flag_odd_hour` — column name contains leakage token 'fraudflag'

## Identifier Columns Removed

- transaction_id
- user_id
- ip_address

## Missing Values

- Before imputation: `{'transaction_id': 0, 'user_id': 0, 'timestamp_utc': 0, 'amount_aed': 0, 'currency': 0, 'payment_method': 0, 'device_type': 0, 'browser': 0, 'merchant_category': 0, 'items_count': 0, 'avg_item_price': 0, 'shipping_city': 0, 'billing_city': 0, 'shipping_billing_match': 0, 'ip_address': 0, 'ip_risk_score': 0, 'card_present': 0, 'bin_country': 0, 'card_age_days': 0, 'card_country_match': 0, 'email_domain': 0, 'user_prev_chargebacks': 0, 'user_is_high_risk': 0, 'user_account_age_days': 0, 'transactions_last_24h': 0, 'transactions_last_1h': 0, 'local_hour': 0, 'odd_hour': 0, 'is_fraud': 0, 'data_source': 0, '_source_row_order': 0, 'timestamp_kst': 0, 'kst_hour': 0, 'day_of_week': 0, 'is_weekend': 0, 'is_night': 0, 'user_avg_hour_prev': 28937, 'hour_deviation': 0, 'amount_krw': 0, 'avg_item_price_krw': 0, 'is_fresh_account': 0, 'city_match': 0, 'log_amount': 0, 'user_avg_amount_prev': 28937, 'user_median_amount_prev': 28937, 'user_std_amount_prev': 54269, 'user_txn_count_prev': 0, 'amount_ratio': 0, 'amount_deviation': 0, 'amount_zscore': 0, 'user_avg_amount_30d': 28937, 'calculated_avg_item_price': 0, 'amount_per_item': 0, 'txn_count_10m': 0, 'txn_count_1h_calc': 0, 'txn_count_24h_calc': 0, 'transactions_last_1h_diff': 0, 'transactions_last_24h_diff': 0, 'high_velocity': 0, 'amount_clipped': 0}`
- After imputation: `{'transaction_id': 0, 'user_id': 0, 'timestamp_utc': 0, 'amount_aed': 0, 'currency': 0, 'payment_method': 0, 'device_type': 0, 'browser': 0, 'merchant_category': 0, 'items_count': 0, 'avg_item_price': 0, 'shipping_city': 0, 'billing_city': 0, 'shipping_billing_match': 0, 'ip_address': 0, 'ip_risk_score': 0, 'card_present': 0, 'bin_country': 0, 'card_age_days': 0, 'card_country_match': 0, 'email_domain': 0, 'user_prev_chargebacks': 0, 'user_is_high_risk': 0, 'user_account_age_days': 0, 'transactions_last_24h': 0, 'transactions_last_1h': 0, 'local_hour': 0, 'odd_hour': 0, 'is_fraud': 0, 'data_source': 0, '_source_row_order': 0, 'timestamp_kst': 0, 'kst_hour': 0, 'day_of_week': 0, 'is_weekend': 0, 'is_night': 0, 'user_avg_hour_prev': 0, 'hour_deviation': 0, 'amount_krw': 0, 'avg_item_price_krw': 0, 'is_fresh_account': 0, 'city_match': 0, 'log_amount': 0, 'user_avg_amount_prev': 0, 'user_median_amount_prev': 0, 'user_std_amount_prev': 0, 'user_txn_count_prev': 0, 'amount_ratio': 0, 'amount_deviation': 0, 'amount_zscore': 0, 'user_avg_amount_30d': 0, 'calculated_avg_item_price': 0, 'amount_per_item': 0, 'txn_count_10m': 0, 'txn_count_1h_calc': 0, 'txn_count_24h_calc': 0, 'transactions_last_1h_diff': 0, 'transactions_last_24h_diff': 0, 'high_velocity': 0, 'amount_clipped': 0}`

## Korea Adaptation
- AED → KRW: numeric AED companion columns are created using configurable `AED_TO_KRW`; original AED values are retained.
- UTC → KST: parsed timestamps are converted to `Asia/Seoul` and used for hour/day features.
- UAE-dependent feature handling: relation features such as country/city match are preferred over raw UAE city/country labels.

## Feature Engineering
- timestamp_utc
- timestamp_kst
- kst_hour
- day_of_week
- is_weekend
- is_night
- user_avg_hour_prev
- hour_deviation
- amount_krw
- avg_item_price_krw
- is_fresh_account
- city_match
- log_amount
- user_avg_amount_prev
- user_median_amount_prev
- user_std_amount_prev
- user_txn_count_prev
- amount_ratio
- amount_deviation
- amount_zscore
- user_avg_amount_30d
- calculated_avg_item_price
- amount_per_item
- txn_count_10m
- txn_count_1h_calc
- txn_count_24h_calc
- transactions_last_1h_diff
- transactions_last_24h_diff
- high_velocity
- amount_clipped

### Outlier Percentiles
- `amount_krw` — `{'p99': 489598.5039999963, 'p99_5': 793607.9495000229, 'p99_9': 1955356.5306000246, 'max': 10650790.399999999}`
- `avg_item_price` — `{'p99': 846.2768999999963, 'p99_5': 1323.1345000000226, 'p99_9': 3261.3642400000163, 'max': 14392.96}`
- `items_count` — `{'p99': 5.0, 'p99_5': 6.0, 'p99_9': 7.0, 'max': 9.0}`
- `transactions_last_1h` — `{'p99': 0.0, 'p99_5': 0.0, 'p99_9': 0.0, 'max': 1.0}`
- `transactions_last_24h` — `{'p99': 0.0, 'p99_5': 1.0, 'p99_9': 1.0, 'max': 2.0}`
- `txn_count_10m` — `{'p99': 1.0, 'p99_5': 1.0, 'p99_9': 2.0, 'max': 3.0}`
- `txn_count_1h_calc` — `{'p99': 2.0, 'p99_5': 3.0, 'p99_9': 3.0, 'max': 5.0}`
- `txn_count_24h_calc` — `{'p99': 6.0, 'p99_5': 7.0, 'p99_9': 8.0, 'max': 11.0}`

### Raw vs Calculated Velocity Comparison
- `transactions_last_1h_diff` — `{'compared_rows': 100000, 'mean_abs_diff': 0.35609, 'max_abs_diff': 5.0, 'mismatch_rows': 29715}`
- `transactions_last_24h_diff` — `{'compared_rows': 100000, 'mean_abs_diff': 1.65857, 'max_abs_diff': 11.0, 'mismatch_rows': 70815}`

## Final Dataset
- Rows: 100000
- Features: 77
- Fraud Ratio: 0.082110
- Numeric Features: amount_aed, items_count, avg_item_price, shipping_billing_match, ip_risk_score, card_present, card_age_days, card_country_match, user_prev_chargebacks, user_is_high_risk, user_account_age_days, transactions_last_24h, transactions_last_1h, local_hour, odd_hour, kst_hour, day_of_week, is_weekend, is_night, user_avg_hour_prev, hour_deviation, amount_krw, avg_item_price_krw, is_fresh_account, city_match, log_amount, user_avg_amount_prev, user_median_amount_prev, user_std_amount_prev, user_txn_count_prev, amount_ratio, amount_deviation, amount_zscore, user_avg_amount_30d, calculated_avg_item_price, amount_per_item, txn_count_10m, txn_count_1h_calc, txn_count_24h_calc, transactions_last_1h_diff, transactions_last_24h_diff, high_velocity, amount_clipped
- Categorical Features: payment_method, device_type, browser, merchant_category, email_domain

## Output Files
- `C:/Users/SSAFY/Desktop/특화/model/data/processed/fraud_processed.csv`
- `C:/Users/SSAFY/Desktop/특화/model/data/processed/fraud_model_features.csv`
- `C:/Users/SSAFY/Desktop/특화/model/data/processed/feature_summary.csv`

## Warnings / Limitations
- Selected label-bearing CSV: uae_ecom_fraud_100k.csv.
- Other discovered CSVs were treated as auxiliary files: category_tree.csv, events.csv, item_properties_part1.csv, item_properties_part2.csv.
- Removed leakage-risk column 'fraud_flag_new_account' because its name contains 'fraudflag'.
- Removed leakage-risk column 'fraud_flag_prev_cb' because its name contains 'fraudflag'.
- Removed leakage-risk column 'fraud_flag_odd_hour' because its name contains 'fraudflag'.
- Parsed timestamp column 'timestamp_utc' as datetime strings / timezone-aware values and converted UTC → KST.
- AED columns were converted with configurable AED_TO_KRW=370.0; the rate is not used as a fraud signal.
- Velocity comparison transactions_last_1h vs txn_count_1h_calc: {'compared_rows': 100000, 'mean_abs_diff': 0.35609, 'max_abs_diff': 5.0, 'mismatch_rows': 29715}
- Velocity comparison transactions_last_24h vs txn_count_24h_calc: {'compared_rows': 100000, 'mean_abs_diff': 1.65857, 'max_abs_diff': 11.0, 'mismatch_rows': 70815}
- Created amount_clipped at quantile 0.995; original 'amount_krw' remains unchanged.
- scikit-learn is unavailable; used pandas.get_dummies for one-hot CSV output. Install scikit-learn for reusable inference encoding.

> 본 데이터는 UAE 전자상거래 환경을 기반으로 생성된 Synthetic Fraud Dataset이며, 한국 실제 금융거래 데이터가 아니다. 따라서 본 전처리는 AI Agent 기반 결제 FDS Prototype 검증을 위한 Domain Adaptation이며 실제 금융기관 Production FDS를 의미하지 않는다.

The pipeline never infers `is_fraud` from event type, transaction status, amount or any other proxy. A label-bearing dataset is required for model training outputs.
