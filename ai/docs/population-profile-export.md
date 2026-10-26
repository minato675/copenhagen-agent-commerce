# 합성 학습 표본으로 집단 프로필 만들기

기존 `learning_samples.jsonl`과 명시적 집단 구성 파일을 읽어 집단 분포와 Story 4 기준
계약 산출물을 저장한다. [집단 통계](population-profiles.md), [가중 방식](population-weighting.md),
[기준 변환 정책과 거부 사유](population-baselines.md)를 먼저 참고한다.

## 고정 데이터 직접 실행

가상환경을 활성화하고 `ai` 폴더에서 실행한다. 출력 폴더는 **처음 사용하는 경로**여야 한다.
아래의 입력 표본은 254에서 만든 자료다. 이번 회귀 검증용 집단 구성 파일은 같은
기준 시각까지 관측한 사용자를 통화별로 나열한 로컬 자료이며 Git에 포함하지 않는다.

```bash
python -m copenhagen_fds.population_export \
  results/evaluation/learning-v1-stream-254/learning_samples.jsonl \
  results/evaluation/population-v1-stream-256-cohorts.json \
  --aggregation USER_EQUAL \
  --output results/evaluation/population-stream-check
```

도전 세트는 두 입력 경로의 `stream`을 `challenge`로 바꾸고 새 출력 폴더를 지정한다.
입력 표본이 없으면 [학습 표본 내보내기](synthetic-learning.md)로 먼저 구성한다.
집단 목록은 숨겨진 사용자 유형·사기 정답을 읽어 구성하지 않는다.

거래별 동일 가중치와 비교할 때는 `--aggregation TRANSACTION`으로 바꾸고 다른 새
출력 폴더를 사용한다. 생략하면 구성 파일 설정을 사용하며 기본은 `USER_EQUAL`이다.

## 집단 구성 파일

`PopulationExportManifest`의 구조는 다음과 같다. 예시의 출처·사용자 ID는 실행에
사용할 표본과 일치하는 실제 입력값으로 교체한다. 위 명령의 로컬 구성 파일에는 이미
고정 표본의 출처와 집단 목록이 기록돼 있다.

```json
{
  "schema_version": "population-export-manifest-v1",
  "origin": "SYNTHETIC",
  "source_reference": "source-reference-from-learning-samples",
  "as_of": "2026-05-01T00:00:00+09:00",
  "config": {"aggregation": "USER_EQUAL", "min_samples": 20, "min_users": 3},
  "cohorts": [{
    "cohort_id": "explicit-cohort-KRW",
    "membership_version": "members-v1",
    "definition": "명시적으로 제공한 사용자 목록",
    "source_reference": "membership-source-v1",
    "available_at": "2026-05-01T00:00:00+09:00",
    "currency": "KRW",
    "member_user_ids": ["user-a", "user-b", "user-c"]
  }]
}
```

한 파일에 여러 집단·통화를 넣을 수 있다. 동일 집단 ID·통화 조합 중복, 중복 사용자,
미래에 알려진 집단 구성과 입력 파일의 다른 기준 시각·출처는 거부한다. 같은 이용자가
서로 다른 명시적 집단에 포함되는 것은 허용하며 각각 독립적으로 집계한다.
빈 학습 파일에서도 제공된 집단 목록은 유지하며 EMPTY 진단을 출력한다.

## 산출물

| 파일 | 내용 |
| --- | --- |
| `population_profiles.jsonl` | 집단·통화별 경험적 분포와 장기 보관 상태 |
| `baseline_derivations.jsonl` | 변환 정책·원본 해시·참조와 Feature별 변환/거부 사유 |
| `population_baselines.jsonl` | 사용 가능한 기준만 기존 PopulationBaseline 형식으로 저장. 전부 거부되면 빈 파일 |
| `report.json` | 집단별 건수·상태·기준 참조·위 세 파일의 SHA-256 |

같은 두 입력과 옵션으로 다른 새 폴더에 다시 실행하면 `artifact_sha256`이 일치하고
보고서를 포함한 네 파일이 각각 바이트 단위로 같아야 한다. 입력/사용자/집단 순서와
같은 내용의 중복 표본은 결과를 바꾸지 않는다. 구성 버전·가중 설정·기준 시각 등이
바뀌면 통계 프로필과 파생 기준의 내용 버전도 바뀐다.

## 고정 자료에서 확인한 결과

기준 시각은 KST `2026-05-01T00:00:00+09:00`이며 기본 최근 7일/장기 90일을 사용했다.

| 세트 | 집단 구성원 / 장기 기여자 | 장기 보존 / 반영 표본 | 최근 상태 | USER_EQUAL / TRANSACTION의 변환 가능 Feature |
| --- | --- | --- | --- | --- |
| 장기 스트림 | 120 / 118 | 8,573 / 1,801 | READY | 7 / 7 |
| 도전 세트 | 60 / 59 | 4,945 / 1,043 | INSUFFICIENT_SAMPLES | 5 / 7 |

두 집단의 장기 상태는 READY이며 기준 변환 상태는 모두 PARTIAL이다. 시간대·요일은
단일 중심으로 지원하기 어려워 두 방식 모두 거부했다. 도전 세트의 사용자 균등 집계는
`purchase_count_10m`, `attempt_count_10m`의 q95와 중앙값이 같아 ZERO_SCALE로 거부했다.
자료가 충분하다는 상태가 모든 Feature를 기존 거리 기준으로 표현할 수 있다는 뜻은 아니다.

평균 구매 금액은 스트림의 거래 집계 약 98,189원 / 사용자 균등 약 81,408원,
도전 세트의 거래 집계 약 73,862원 / 사용자 균등 약 68,206원이었다.
이 차이는 가중 방식의 영향이며 어느 쪽의 탐지 성능이 더 좋다는 결과가 아니다.
각 세트와 두 가중 방식의 네 산출물을 재실행하여 총 16개 파일 쌍의 일치를 확인했다.

이 실행은 전체 기간의 입력 연결·분포·계약 회귀 검증이다. 학습/테스트로 분리한 탐지
성능 평가는 아니다. 학습 구간을 사용할 때는 먼저 254 재생을 학습 종료 시각으로 하고,
그 시각까지 알 수 있었던 집단 목록과 같은 `as_of`를 제공해야 한다. 전체 기간에서 만든
집단 목록의 가용 시각만 앞당겨 학습 시점에 이미 알았던 것처럼 사용하지 않는다.

CLI의 생성·기준 가용 시각은 재현용 논리 시각으로 `as_of`와 같고 유효기간은 1일이다.
과거 결과를 현재 운영에 유효한 기준으로 사용한다는 뜻이 아니다. 실사용 호출자는
생성 함수와 변환 요청에 실제 생성·가용 시각과 유효기간을 제공한다.
현재 산출물은 모델 점수 계산이나 GPT 결합을 실행하지 않으며 외부 API 키·DB가 필요 없다.

## 파일 경계 직접 검증

```bash
python -m pytest tests/learning/test_population_export.py -v --tb=short
```

9개 검증이 통과해야 한다. 입력 격리·내용 버전·재실행 일치·기존 폴더 보존·빈 자료를 확인한다.
