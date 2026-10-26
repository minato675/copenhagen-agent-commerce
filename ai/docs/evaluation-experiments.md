# GPT 평가 실험 도구

기존 26건 회귀, 고정 비교 입력의 반복 관측, 별도 평가셋의 전체 분석을 명시적인 실행 계획으로 수집한다. 새 원본·실행 계획·보고서는 Git 제외 경로 `results/evaluation/`에 저장한다. 구매·주문 사실이 포함된 원본을 애플리케이션 로그에 출력하지 않는다.

## 계획과 관측

`ExperimentPlan`은 평가셋 전체 내용·선택 사례·반복 수·요청 모델·프롬프트/스키마·입력 해시·타임아웃·호출 상한을 고정한다. 계획의 SHA-256이 바뀌면 기존 실행 동의의 대상과 달라진다. 실제 수집은 현재 프롬프트와 설정이 계획의 고정값과 일치해야 시작한다. 보고서는 모델·프롬프트 버전과 계획/데이터 해시를 기록한다.

`PIPELINE`은 추출과 비교를 함께 실행한다. `COMPARISON_REPEAT`는 고정된 `FraudEvaluationRequest`와 확인 사항만 비교하며 추출을 호출하지 않는다. 비교 입력 출처는 `REFERENCE_INTENT`(검증용으로 정의한 의도) 또는 `GMS_EXTRACTED_INTENT`(기존 실제 추출)로 구분한다. 정의한 의도를 실제 모델 추출 결과로 보고하지 않는다. 비교만 실행한 관측에는 `IntentComparisonResult`만 보관하고 추출 점수·토큰을 새 측정에 포함하지 않는다.

관측 식별자는 `(case_id, repetition)`이며 `run_id`는 `case_id.r1` 형식이다. 동일 식별자의 중복 관측, 계획 밖의 실행, 입력 해시 변경, 프롬프트/요청 모델 혼합, 단계 내 응답 모델 버전 혼합은 채점 오류로 거부한다. 반복할 서로 다른 사례의 전송 메시지 해시가 같아도 계획 생성 시 거부한다. 반복은 사례별 5회를 몰아서 실행하지 않고 회차별로 모든 사례를 순회한다.

## 오프라인 계획 생성

```powershell
# ai 디렉터리에서 실행. 이 명령은 환경 파일·키를 읽거나 API를 호출하지 않는다.
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation.experiment_cli --experiment-id regression-v1 --max-calls 52 --output results/evaluation/regression-plan.json

# 별도 정의한 20개 입력을 각 5회 비교하는 계획
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation.experiment_cli --experiment-id stability-v1 --dataset-file results/evaluation/stability-dataset.json --dataset-role STABILITY --mode COMPARISON_REPEAT --repetitions 5 --max-calls 100 --output results/evaluation/stability-plan.json
```

`--case-id`를 여러 번 전달하면 부분집합을 선택할 수 있다. `--frozen-from`에는 기존 `ObservationBatch`를 지정할 수 있으며 같은 평가셋의 성공한 추출만 고정 입력으로 변환한다. 원본 사례의 대상·출처 연결도 검사한다. 기존 계획 파일은 덮어쓰지 않는다.

## 실제 수집

실행 전 계획에 포함된 사례·모델·반복 수·최대 호출 수와 예산 범위를 승인받는다. 명령의 해시 인자는 동의 대상을 연결하는 수단이지 사람의 동의나 정답 검토를 자동으로 증명하는 장치는 아니다.

```powershell
# 승인받은 실제 계획 해시를 사용한다. 이 명령은 유료 GMS 호출을 수행한다.
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation.collect_gms --plan results/evaluation/regression-plan.json --approve-plan-sha256 <approved-plan-sha256> --env-file .env --output-dir results/evaluation/regression-run
```

호출 경로는 기존 GMS Chat Completions 어댑터다. 모델은 `gpt-5.4-mini`, SDK/수집 재시도는 0회, 기본 수집 타임아웃은 제공자 60초·전체 120초다. 타임아웃은 수집 상한이며 4초 서비스 지연 목표와 다르다. 리다이렉트와 환경 프록시는 비활성화한다.

수집기는 실제 HTTP 요청 직전에 횟수와 메시지 해시를 저널에 기록한다. 호출 상한을 넘는 요청이나 동일 실행 단계의 재시도는 전송 전에 중단한다. 프로세스가 그 사이 종료되면 실제 전송 여부를 확정할 수 없는 기록도 남으며 새 실행에서 몰래 재시도하지 않는다. 400/401/403/404/422/429 응답, API 오류·시간 초과 3회 연속, 사용자 중단, 로컬 오류도 중단 상태와 수집된 관측을 보존한다. 기존 결과 디렉터리 재사용은 거부한다.

관측 파일은 임시 파일을 완성한 뒤 같은 디렉터리에서 교체한다. 중단 기록을 검토하고 남은 범위를 새로 승인한 계획으로 실행해야 하며 자동 재개·성공할 때까지 재실행하는 기능은 없다. 요청 헤더·키·제공자 원문·예외 상세는 저널에 포함하지 않는다.

현재 GMS 청구 단가를 받지 못하므로 금액 제한을 집행한다고 주장하지 않는다. 계획은 `CALL_COUNT_ONLY_UNPRICED`를 명시하며 호출 수만 제한한다. 실제 금액 상한이 필요하면 제공자 쪽 제한을 별도로 확인한 뒤 실행한다. 토큰 누락과 청구 금액은 추정하지 않는다.

## 오프라인 채점

```powershell
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation score-gms --plan results/evaluation/regression-run/plan.json --observations results/evaluation/regression-run/observations.json --output results/evaluation/regression-run/report-cli.json
```

기존 `score-gms`를 확장한 경로이며 DNS·소켓 연결을 차단한다. `--plan`을 생략한 기존 26건 관측 채점과 v1/v2 채점기 재현 기능은 유지한다. 실험 채점기 `fds-experiment-grader-v1`은 v2의 필드·사유·참조 검사 함수를 재사용하며, 고정 v1 회귀셋의 동등 표현만 기존 사례 ID에 적용한다. 별도 평가셋의 정답은 그 평가셋에 고정된 필드 기준과 완전한 사유/확인 조합을 사용한다.

- 라벨·필드·사유/확인/참조·전체 사례 통과율을 구분한다. 누락 실행은 `MISSING`, API·검증 실패는 `FAILED`, 정상 미실행은 `NOT_RUN`으로 표시한다.
- 계획한 모든 실행이 분모에 들어간다. 실패·누락을 제외한 성공 사례만의 정확도를 대표값으로 사용하지 않는다.
- 반복 일관성은 입력별 최빈 **유효 라벨**의 횟수를 합한 값 / 전체 계획 시도 수다. 실패·누락은 분모에만 포함한다. 동률인 라벨은 모두 표시하고 정확도와 최빈 라벨의 정답 여부도 함께 보고한다.
- 공격/핵심 사례의 잘못된 MATCH, 실행 실패, 누락을 따로 집계한다. 검증 실패를 공격 방어 성공으로 세지 않는다.
- 부분 계획, 중단, Mock 또는 검토하지 않은 독립 평가셋은 전체 품질 목표를 달성했다고 표시하지 않는다. 반복 목표 판정에는 서로 다른 20개 입력 × 5회 조건도 필요하다.
- 보고서의 `GMS_IMPORTED`는 수집 기록이 주장하는 출처다. 로컬 파일의 진위나 사람 검토를 독립적으로 인증하는 기능은 없다.

단계별 제공자 시간·토큰과 실제 수집 경과 시간을 구분한다. 전체 경과 시간에는 수집 저널 쓰기와 로컬 검증도 포함된다. 실패·미실행도 전체 경과 시간에 남기며 두 단계가 HTTP로 호출된 경우의 분포를 따로 제공한다. p95는 nearest-rank다. 관측 없는 요청은 `requests_without_observation`에 남고 그 지연·사용량을 추정하지 않는다. 비교 반복의 시간은 `COMPARISON_ONLY`이며 전체 파이프라인 지연으로 보고하지 않는다.

`quality_targets`는 의미 품질 기준이며, 4초 p95 여부는 별도 `pipeline_latency_target`에 기록한다. 실제 전체 프로토콜을 완료하고 호출된 두 단계의 시간이 모두 관측됐을 때만 판정한다. 반복 비교만의 시간이나 누락된 시간을 전체 파이프라인 목표 통과로 표시하지 않는다. 다른 입력 크기나 실행 조건까지 같은 지연을 보장하는 값은 아니다.

## 동일 채점 기준의 전후 비교

```powershell
.\.venv\Scripts\python.exe -X utf8 -m copenhagen_fds.evaluation.compare_runs --before results/evaluation/baseline/observations.json --after-plan results/evaluation/regression-run/plan.json --after results/evaluation/regression-run/observations.json --output results/evaluation/regression-run/before-after.json
```

기존 `ObservationBatch`와 새 전체 파이프라인 관측을 오프라인에서 재채점한다. 입력 ID 집합·데이터셋 해시·출처가 다른 비교나 불완전한 새 관측은 거부한다. 전후에 동일 v2 기준을 적용하고, 과거 응답 자체에 v1/v2를 각각 적용한 채점 기준 변경 효과도 분리한다. 변경된 사례별 라벨·필드·사유 검사와 양쪽 단계별 사용량을 함께 남긴다. 예전 관측 형식에 없던 전체 경과 시간은 단계별 시간으로 만들어내지 않는다. 새 파일에만 저장하며 기존 관측이나 보고서를 덮어쓰지 않는다.

## 독립 평가셋 검토

```powershell
# 외부 호출 없이 후보 데이터, 중복 검사, 검토 HTML, 계획을 새 디렉터리에 생성한다.
.\.venv\Scripts\python.exe -X utf8 -m copenhagen_fds.evaluation.suite_export --output-dir results/evaluation/suite-candidates
```

`suite_cases.py`는 100개 사실 상황의 충족/위반/정보 부족 변형 300건과, 별도 반복 비교용 20건을 정의한다. 상품 속성·브랜드·복수 조건·제외 조건·다중 판매처·수량 누락·예산 모호함·선호/충돌·모델 대체를 포함한다. 공격 60건은 요청 원문·상품명·설명에 분산하며 정답별 20건이다. 조건이 다른 반사실 사례는 같은 그룹으로 표시한다. 이는 프롬프트 개발에 미사용인 후보 데이터이며 통계적으로 독립된 무작위 300개 표본을 의미하지 않는다.

생성 결과의 `manifest.json`에는 작성 방식·생성 코드 해시·데이터 버전/해시·사례 해시·정답·근거·분류·그룹을 남긴다. `holdout-review.html`은 라벨/유형/검색 필터와 요청·주문·기준 답안·채점 항목을 보여준다. 공격 문자열도 HTML로 실행하지 않고 이스케이프한 텍스트로 표시한다. 페이지에는 승인 제출 기능이 없다. 생성 자료와 실행 결과는 Git에서 제외한다.

중복 검사는 식별자·공백·대소문자·전각 차이를 제거한 실제 비교 입력을 사용한다. 회귀셋, 반복셋, 프롬프트 예제와 완전히 같은 입력은 내보내기를 거부한다. 비교 요청으로 변환할 수 없는 개발용 경계 사례는 제외 ID로 보고한다. 값 토큰의 Jaccard 유사도 0.8 이상인 쌍은 `audit.json`의 검토 후보이며, 이 휴리스틱만으로 의미 중복이 없다고 판정하지 않는다.

새 후보의 필드 채점은 명시 상품명·수량·브랜드·예산 값과 선택 필드의 null/존재 여부를 검사한다. 카테고리와 지정 브랜드를 조건 필드에 중복 기록했는지는 채점하지 않는다. 자유 서술 조건·선호·모호함은 역할별 필드 존재 여부만 검사하므로 해당 지표를 전체 문장의 의미 정확도로 해석하지 않는다. 의미 라벨과 완전한 사유/확인/참조 검사는 별도 지표이며, 필드 채점 범위와 답안은 사람 검토 시 함께 확인한다. 기존 회귀셋의 필드 기준은 바꾸지 않는다.

HOLDOUT은 MATCH/MISMATCH/UNKNOWN 각 100건을 가진 300건 평가셋이다. 후보 정답은 평가 대상 모델의 응답으로 생성하거나 수정하지 않는다. 사람 검토 전에는 `human_review=null`이며 실제 호출을 거부한다. 승인 기록은 정확한 `dataset_sha256`, 전체 `reviewed_case_ids`, `reviewer`, `reviewed_at`, `decision=APPROVED`를 포함해야 한다. 검토된 데이터가 바뀌면 이전 승인은 적용되지 않는다.

프롬프트 개발 사례와의 중복, 단순 문장 변형 여부, 범주·근거·출처를 검토한다. 균형 합성 평가셋의 결과는 실제 요청 분포의 성능과 같지 않다. 평가 후 오류를 프롬프트 개선에 사용하면 해당 데이터는 더 이상 그 개선 버전의 미사용 독립 평가셋이 아니므로 이력을 남기고 새 독립 데이터로 재검증한다.
