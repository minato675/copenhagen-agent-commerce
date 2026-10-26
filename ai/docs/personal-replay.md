# 합성 이력에서 개인화 평가 재생

`run_personal_replay`는 관측 자료로 시점별 개인 프로필을 만들고 기존 학습 반영 정책의
`published` 결과를 개인화 평가기에 전달한다. `PersonalReplayRequest`에는 실제 설정과
평가별 입력·프로필 기준 시점을 명시한다. 파일 읽기·내보내기는 CLI가 담당한다.

## 시점과 데이터 흐름

```text
users / purchases / events / outcomes / feedback / coverage_gaps
  → 사용자별 관측 자료
  → profile_as_of까지 도착한 구매·이벤트·후속 확인만 선택
  → 기존 학습 적격 판정 + 구매 당시 Raw Feature 재생
  → 개인 통계 후보 + 학습 반영 정책
  → published 개인 프로필
  → 평가 거래 Raw Feature + 지정한 초기 Cold 정책
  → Task 269 개인화 평가
  → 상세 결과 + Cold 대비 점수·비중·단계 비교
```

`profile_as_of < evaluated_at`을 지킨다. 프로필 시각과 평가 시각은 호출자가 정하며 시스템
현재 시각을 사용하지 않는다. 생성·공개 시각을 과거 cutoff로 두는 것은 합성 자료의 오프라인
시뮬레이션이다. 운영 서비스에서 당시 프로필이 실제 공개됐다는 증거를 만드는 기능은 아니다.

프로필에는 기존 `confirmed-normal` 적격 판정과 admission 정책을 적용한다. `feedback`의
실제로 도착한 정상/사기 확인은 학습 근거로 사용할 수 있지만, 미래 확인은 제외한다.
`truth.jsonl`, `truth.csv`, `personas.jsonl`은 열지 않는다. 대상 선택에도 정답·시나리오 이름·
점수·숨겨진 사용자 분포를 사용하지 않는다. 이 도구는 정확도나 사기 적중률을 집계하지 않는다.

현재 평가의 구매 ID는 프로필 재생에서 제외한다. 과거 구매를 나중에 다시 평가하더라도
해당 구매를 후보/공개 통계에 섞지 않는다. 프로필 버전은 선택된 관측·설정·cutoff로 결정하며
미래 레코드나 정답 파일의 해시로 정하지 않는다. 원본 관측 파일 전체의 무결성 해시는
별도 내보내기 보고서에 기록한다.

## 두 실행 방식

`dataset_cases`는 지정 구간에 도착한 구매를 가용 시각·ID 순서로 선택한다. 실제 주문 금액,
구매 시각과 당시 보이던 이력을 기존 `ReplayIndex`와 `transform_features`로 변환한다.
구매 도착과 마지막 재시도는 별도 평가점이며 구간 종료 이후의 재시도는 제외한다.
따라서 구매 limit가 6이어도 평가점은 7개 이상일 수 있다. 학습 이력에는 limit를 적용하지 않는다.

`comparison_cases`는 여러 이용자에게 동일한 금액·발생 시각·직전 24시간 구매/시도 0건을
가정한 `CONTROLLED_PROBE`를 만든다. 이는 프로필 효과를 확인하는 통제 입력이며 실제 관측된
그 이용자의 최근 활동을 대신 설명하지 않는다. 기본 이용자는 최초 cutoff까지 수집이 시작된
이용자의 ID 순서로 선택한다. `--users`로 비교 대상을 명시할 수도 있다.

비교는 개인 프로필 미제공 Cold, 각 cutoff의 공개 프로필, 마지막 프로필의 만료 이후 순서다.
각 시점에서 이용자 간 Raw Feature는 같지만, 이력이 축적되는 서로 다른 날짜 사이에는 요일이
달라질 수 있다. 만료 평가에는 마지막 공개 프로필과 마지막 probe 발생 시각을 그대로 사용한다.
개인화 단계에 도달하지 않은 이용자는 그 상태를 그대로 출력한다.

```python
from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest
from copenhagen_fds.personal_replay import dataset_cases, run_personal_replay

cases = dataset_cases(observations, profile_as_of="2026-04-01T00:00:00+09:00",
                      start="2026-04-01T00:00:00+09:00", end="2026-04-02T00:00:00+09:00", limit=6)
request = PersonalReplayRequest(settings=settings, cases=cases)
result = run_personal_replay(observations, request)
```

입력·설정·공개 프로필은 결과에 보존하며 원본 자료를 갱신하지 않는다. Cold는 명시적인 초기
정책을 사용한다. 집단 프로필 자동 학습, 그룹 가중합, GPT 결합과 서비스 저장은 이 재생기의
범위가 아니다. [개인화 평가](personal-evaluation.md)의 계산·실패·척도 정책을 재사용한다.

## 손계산 경계 예제

테스트의 이용자 a는 평소 금액 10,000원, b는 100,000원이며 현재 probe는 둘 다 30,000원이다.
테스트 전용 충분도 설정은 최소 2개·충분 4개이고 다른 충분도 축은 충족시킨다. Cold는 0점이다.

| 개인 표본/상태 | a 단계 | 개인 비중 | a 최종 점수 | b 최종 점수 |
| --- | --- | --- | --- | --- |
| 프로필 미제공 | COLD | 0 | 0 | 0 |
| 2개 | COLD | 0 | 0 | 0 |
| 3개 | TRANSITION | 0.5 | 50 | 0 |
| 4개 | PERSONALIZED | 1 | 100 | 0 |
| 같은 4개 프로필 만료 | COLD | 0 | 0 | 0 |

실제 프로필 생성·정책 적용·Feature 변환·점수 계산을 사용한 예제이며 결과 점수를 직접 넣지
않는다. 위 설정은 경계 검증용이다. CLI의 예시 설정은 최소 5개·충분 30개 및 관측 기간
7일/28일 등을 명시한 별도 UNVALIDATED 설계값이며 운영 권장 임계값이 아니다.

실행 방법과 파일 검증은 [개인화 재생 CLI](personal-replay-cli.md)에 설명한다.
