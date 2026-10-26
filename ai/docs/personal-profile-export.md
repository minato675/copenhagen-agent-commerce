# 합성 학습 표본으로 개인 프로필 만들기

254에서 내보낸 `learning_samples.jsonl`을 읽어 사용자·통화별 `PersonalProfileSnapshot`을 만듭니다. 이 경로는 정답·시나리오·숨겨진 사용자 분포나 외부 API를 읽지 않습니다. [개인 프로필의 동작](personal-profiles.md)을 먼저 참고하세요.

가상환경을 활성화하고 `ai` 폴더에서 실행합니다. 기존 결과를 덮어쓰지 않으므로 **처음 사용하는 출력 폴더**를 지정해야 합니다.

```bash
python -m copenhagen_fds.profile_export \
  results/evaluation/learning-v1-stream-254/learning_samples.jsonl \
  --as-of '2026-05-01T00:00:00+09:00' \
  --output results/evaluation/personal-stream-check
```

도전 세트는 입력을 `results/evaluation/learning-v1-challenge-254/learning_samples.jsonl`, 출력을 `results/evaluation/personal-challenge-check`로 바꿉니다. 입력이 없으면 [학습 표본 내보내기](synthetic-learning.md)로 먼저 구성합니다. 같은 기준 시각의 표본만 허용합니다.

결과는 `personal_profiles.jsonl`과 `report.json`입니다. JSONL 한 줄이 사용자·통화별 프로필 하나이며, 사용자의 원시 참조를 포함하므로 운영 자료에서는 원문을 공개 로그에 출력하지 않습니다. Git에서는 기존 `results/evaluation/` 제외 규칙을 적용합니다.

기본 설정으로 고정 표본을 실행한 결과는 다음과 같습니다. 출력의 `READY`는 정상 확인 표본 수 5개 이상을 뜻합니다.

| 세트 | 프로필 | 장기 범위 보존 표본 | 장기 학습 반영 | 장기 READY / 부족 / EMPTY | 최근 READY / 부족 / EMPTY |
| --- | ---: | ---: | ---: | --- | --- |
| 장기 스트림 | 120 | 8,573 | 1,801 | 95 / 23 / 2 | 0 / 29 / 91 |
| 도전 세트 | 60 | 4,945 | 1,043 | 52 / 7 / 1 | 0 / 9 / 51 |

원천 120일 중 장기 통계에 최근 90일만 포함하므로 254의 전체 표본 수보다 보존 수가 작습니다. 정상 확인이 도착하지 않은 구매는 보류하므로 최근 표본이 적을 수 있습니다. 최소 표본 수나 최근 기간을 바꿔 실험하려면 `--min-samples`, `--recent-days`, `--long-term-days`를 명시합니다. 표본 수를 맞추기 위해 보류된 구매를 임의로 정상으로 바꾸지는 않습니다.

같은 명령을 다른 새 출력 폴더로 다시 실행하면 `profiles_sha256`이 같아야 하며, 결과 파일 두 개가 각각 바이트 단위로 일치해야 합니다. 프로필 ID는 사용자·통화에서, 프로필/상태 버전은 표본·설정·기준 시각·출처에서 결정적으로 만듭니다. 저장 요청마다 새로운 버전을 임의 생성하지 않습니다.

CLI의 `generated_at`은 재현을 위한 논리 생성 시각으로 `as_of`와 같고, `valid_until`은 그로부터 1일 뒤입니다. 과거 시점 실험 결과를 현재 운영에서 유효한 프로필이라고 주장하는 값이 아닙니다. 실사용 호출자는 생성 함수의 `PersonalProfileRequest`에 실제 생성 시각과 유효기간을 명시합니다. 보고서의 UTC 시각 `2026-04-30T15:00:00+00:00`은 위 KST 기준 시각과 같습니다.

위 5월 1일 재생은 전체 기간의 연결·회귀 확인용이며 탐지 성능을 평가하는 학습/테스트 분할이 아닙니다. 학습 구간만 사용할 때는 254의 `export-learning`을 학습 종료인 `2026-03-02T00:00:00+09:00`으로 먼저 실행하고, 같은 시각으로 이 명령을 실행합니다. 현재 단계에서는 프로필을 생성하며 위험 점수나 최종 사기 판정을 계산하지 않습니다.

CLI·파일 격리·재현성 검증:

```bash
python -m pytest tests/learning/test_profile_export.py -v --tb=short
```

예상 결과: **6 passed**.
