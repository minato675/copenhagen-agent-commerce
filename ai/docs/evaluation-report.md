# GPT 분석 집계와 실제 응답 평가

집계 도구는 로컬에서 동작하며 GMS·OpenAI Evals API를 호출하지 않습니다. 고정 입력과 기대값은 [평가셋 문서](evaluation-cases.md)에 설명합니다.

실행 번호·고정 입력 해시를 가진 반복/독립 관측은 `score-gms --plan`으로 채점합니다. 별도 실제 수집 명령과 호출 제한·사람 검토 절차는 [평가 실험 도구](evaluation-experiments.md)를 참고합니다. 기본 집계 명령은 계속 오프라인입니다.

## 명령

```powershell
# ai 디렉터리에서 실행: 고정 Mock 응답으로 전체 분석 경로 검증
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation mock --output results/evaluation/mock-v1.json

# 별도로 수집한 실제 GMS 응답만 로컬에서 채점. 이 명령은 새 API 요청을 보내지 않음
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation score-gms --observations results/evaluation/gms-observations.json --output results/evaluation/gms-report.json
```

`--output`을 생략하면 안전한 집계 보고서만 표준 출력으로 내보냅니다. 출력 경로가 이미 있으면 보고서를 갱신하지만 원본 observations 파일을 출력으로 덮어쓰는 것은 거부합니다. `results/evaluation/`은 Git에서 제외합니다. 종료 코드는 모든 관측 사례의 판정 통과 0, 판정 실패 포함 1, 입력·오프라인 경계·파일 오류 2입니다. 일부 사례만 관측한 경우에는 통과 코드와 별개로 coverage를 반드시 확인합니다.

명령에는 `live` 모드나 자동 실제 호출 옵션이 없습니다. 두 모드 모두 DNS·소켓 연결을 차단합니다. 프로세스의 LIVE 설정이나 GMS 키가 있어도 Mock 실행은 명시적 MOCK 설정만 사용합니다. 사용자·주문 식별자, 원본 입력·응답, 예외 상세를 보고서나 오류 메시지로 출력하지 않습니다.

## 보고서 의미

| 항목 | 의미 |
| --- | --- |
| source=MOCK | 스크립트 응답 기반 회귀. mock_regression_metrics만 채움 |
| source=GMS_IMPORTED | 제공받은 GMS 관측 형식의 결과를 채점. imported_response_metrics만 채움 |
| dataset_version / dataset_sha256 / grader_version | 평가셋 내용과 채점 기준의 버전 |
| versions | 단계별 요청 모델·관측 응답 모델·프롬프트 버전 집합 |
| eligible_case_count / evaluated_case_count / missing_case_ids | 대상 수·실제 관측 수·누락 사례 |
| cases | 사례 ID, 통과 여부, 필드별 판정, 단계 상태·실패 종류. 실제 필드값은 제외 |

MOCK 보고서의 imported_response_metrics와 provider_metrics는 null입니다. 값이 100%여도 실제 모델의 정확도나 지연·토큰 사용량으로 보고하지 않습니다. 실험용으로 인위적으로 변조한 관측값을 사용하는 채점기 테스트도 실제 GMS 품질 결과가 아닙니다.

GMS_IMPORTED는 입력 파일이 주장한 출처입니다. 도구는 두 단계의 gateway·공통 출처 계약을 검사하며 MOCK 관측을 그대로 받아 실제 결과로 이름만 바꾸는 것을 거부합니다. 그러나 로컬 파일의 진위를 독립적으로 인증하지 않습니다. 보고서에도 `PROVENANCE_NOT_INDEPENDENTLY_VERIFIED`를 표시합니다. 수집 실행·동의·출처를 별도로 기록한 관측 파일만 실제 모델 분석에 사용합니다.

## 지표의 분모와 오류 구분

- 추출 필드 일치율은 통과한 필드 판정 수 / 기대 필드 판정 수입니다. 기대상 추출 성공이어야 하는 사례가 실패하면 해당 필드 판정은 실패로 포함합니다. 기대상 실행 실패인 사례에 가상의 추출 정답 점수를 만들지 않습니다.
- 혼동 행렬은 기대 MATCH/MISMATCH/UNKNOWN을 행으로, 실제 MATCH/MISMATCH/UNKNOWN/FAILED/NOT_RUN을 열로 기록합니다. 실패를 UNKNOWN이나 MATCH로 바꾸지 않고 분모에서 빼지 않습니다. 기대상 오류인 사례는 별도 실행 상태·실패 종류 집계에 남습니다.
- false_mismatch는 기대 MATCH→실제 MISMATCH, missed_mismatch는 기대 MISMATCH→실제 MATCH입니다. 기대 MISMATCH→실제 UNKNOWN은 mismatch_abstained, 기대 UNKNOWN→실제 MATCH는 unknown_collapsed_to_match로 별도 표시합니다. 금융 사기 탐지의 오탐·미탐률을 뜻하지 않습니다.
- 불확실성 보존율은 해당 기준이 있는 사례만 분모로 사용합니다. 추출 확인 사항과, 비교가 MATCH로 축약되지 않고 필요한 확인 요청을 유지하는지 사례별로 검사합니다. 비일치 근거와 불확실성이 함께 있으면 MISMATCH에서도 확인 사항을 보존할 수 있습니다.
- 필수 사유는 코드·차원·관련 항목으로, 확인 사항은 코드·관련 항목으로 검사합니다. 사유 순서는 바뀔 수 있지만 관련 상품을 다른 주문의 상품으로 바꿀 수 없습니다.
- 분모가 0인 비율은 0% 대신 null입니다. 일부 관측만 가져온 경우 누락 사례를 별도로 표시하며 전체 평가를 수행한 것처럼 보고하지 않습니다.

GMS_IMPORTED 보고서는 전달받은 단계별 시간의 평균·중앙값·nearest-rank p95, 보고된 실패 HTTP 상태·실패 종류와 토큰 합계를 집계합니다. 토큰 필드별 보고 수와 누락 수를 표시합니다. 모든 사용량이 누락되면 합계는 null이며, 명시적 0은 실제 보고된 0으로 보존합니다. 알려진 일부 합계를 전체 사용량으로 보거나 빠진 호출 시간·사용량을 추정하지 않습니다. GMS 청구 정보를 받지 않으므로 비용은 null입니다.

## 채점 기준 선택

기본 채점기는 `fds-intent-grader-v2`다. [판정 기준 v2](intent-evaluation-rubric.md)는 변경하지 않은 v1 입력에 대해 유한한 동등 표현을 허용하며 사유·확인 조합을 더 엄격히 검사한다. 기존 결과 재현에는 `--grader-version fds-intent-grader-v1`을 사용한다. 관측 파일의 버전이나 해시를 바꿀 필요가 없다. v2 보고서는 `rubric_sha256`, 의미 라벨 및 사유·확인 조합의 개별 비율을 추가한다.

```powershell
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation score-gms --observations results/evaluation/observations.json --grader-version fds-intent-grader-v1 --output results/evaluation/report-v1.json
.\.venv\Scripts\python.exe -m copenhagen_fds.evaluation score-gms --observations results/evaluation/observations.json --grader-version fds-intent-grader-v2 --output results/evaluation/report-v2.json
```

같은 저장 응답의 v1/v2 차이는 채점 기준 변경 효과다. 모델 개선 폭은 같은 채점기로 개선 전후 응답을 비교한다. 원본 응답과 기존 보고서를 덮어쓰지 않는다.

## 실제 응답을 준비하는 별도 경로

실제 호출을 수행하기 전 사용자와 모델, 고정 합성 사례 ID 목록, 요청 횟수, GMS에서 관리할 비용 상한을 확정합니다. 과거 단일 smoke test 동의는 전체 평가셋 재호출 동의가 아닙니다. 실제 호출·비용 집행은 기존 LIVE `IntentAnalyzer`를 명시적으로 실행하는 수집 단계에서 담당하며, 이 집계 도구는 비용 제한 기능을 제공하지 않습니다.

실제 수집에는 `load_dataset()`에서 `fault is None`인 26개 사례 중 합의한 부분집합을 선택합니다. 나머지 9개는 Mock에서 인증·스키마 등의 오류를 강제로 만드는 제어 사례이며 모델 품질 입력에서 제외됩니다. 실제 결과를 성공으로 만들기 위해 재시도하거나 사유·예산을 고쳐서 저장하지 않습니다. 미수집 사례는 관측값을 만들지 않습니다.

`ObservationBatch` 형식은 다음과 같습니다. `run`에는 해당 사례의 `IntentAnalyzer.analyze()`가 반환한 전체 `IntentAnalysisRun.model_dump(mode="json")` 값을 넣습니다.

```text
dataset_version: load_dataset().version
dataset_sha256: load_dataset().sha256
source: GMS_IMPORTED
observations:
  - case_id: 실제 수집한 사례 ID
    run: 원래 대상·출처·메타데이터·측정값을 보존한 분석 결과
```

원래 평가셋과 버전·해시·사례 ID·대상 참조가 다르거나 중복 관측이 있으면 거부합니다. 일부 실패만 누락해 결과가 좋아 보이게 만드는 것을 방지하려면 수집 단계에서 합의한 사례 목록과 관측 목록을 별도로 대조해야 합니다. 응답 파일에는 추출된 합성 구매 정보가 포함되므로 전체 로그에 출력하지 않습니다.

고정 문자열 판정은 의미 동등성과 달라질 수 있고, 사례 수가 작아 일반적인 모델 품질을 대표하지 않습니다. [OpenAI Graders 문서](https://developers.openai.com/api/docs/guides/graders)에서 설명하는 값 비교·유사도 등의 구분을 참고하되, 여기서는 비용 없는 로컬 판정만 사용합니다. 모델 판정기·운영 성능 기준·자동 배포 승인 기준을 추가하지 않았습니다.
