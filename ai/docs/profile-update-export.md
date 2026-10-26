# 프로필 갱신 직접 검증

아래 명령은 저장소의 `ai/`에서 가상환경을 활성화한 뒤 실행한다.
`python 파일명.py`가 아니라 `python -m pytest`를 사용해야 테스트가 실행된다.
외부 API 키나 서비스 DB는 필요하지 않다.

## 완료 기준별 검증

| 확인할 동작 | 명령 | 확인할 결과 |
|---|---|---|
| 중복·순서 역전·지연 전달 | `python -m pytest tests/learning/updates/test_revision_log.py tests/learning/updates/test_profile_updates.py -v --tb=short` | 42 passed. 중복 재반영 없음, 낮은 수정 번호 무시, 중간 번호 누락 시 재생 필요 |
| 기대 버전·이전 상태 보존·변경 근거 | `python -m pytest tests/learning/updates/test_profile_updates.py tests/learning/updates/test_export.py -v --tb=short` | 30 passed. VERSION_MISMATCH 거부, 기존 상태 불변, 새 상태·처리 이유·전후 해시 반환 |
| 뒤늦은 확인·정정과 전체 재생 동등성 | `python -m pytest tests/learning/updates/test_replay.py -v --tb=short` | 11 passed. 개인·집단의 최근/장기 통계와 보관 표본 일치, 누락시킨 피처 정정은 불일치 탐지 |
| JSON 복원·정확한 분위수·보관 부족 | `python -m pytest tests/learning/updates/test_profile_updates.py -v --tb=short` | 22 passed. 복원 후 정정 결과 동일, 기간 확대/이력 누락 등에서 REPLAY_REQUIRED |

모두 한 번에 확인하려면:

```bash
python -m pytest tests/learning/updates -v --tb=short
```

61개 테스트가 실행된다. 숫자는 매개변수별 사례를 포함한 실행 개수다.
전체 기존 기능 회귀 검증은 `python -m pytest -q --tb=short`로 실행한다.

## JSON 명령 사용

초기 통계 스냅샷을 수정 보관 상태로 바꾼다. 여러 프로필을 담은 JSONL이면
`--profile-id`로 하나를 지정한다.

```bash
python -m copenhagen_fds.profile_update_export init snapshot.json --output results/evaluation/update-initial
```

갱신 요청 JSON에는 기대 상태 버전, 기대 프로필 참조, 새 기준/생성/만료 시각,
변경 이력의 완전한 시작/종료 시각을 입력한다. 직접 사건을 공급할 때는 `events`에
`ProfileUpdateEvent`를 넣고 `apply`를 사용한다.

```bash
python -m copenhagen_fds.profile_update_export apply results/evaluation/update-initial/state.json request.json --output results/evaluation/update-next
```

전체 학습 재생 표본으로 비교할 때는 요청의 `events`를 비우고 `replay`를 사용한다.
마지막 인수의 JSONL은 새로운 사건 일부가 아닌 **새 기준 시점의 완전한 학습 표본**이다.

```bash
python -m copenhagen_fds.profile_update_export replay results/evaluation/update-initial/state.json request.json learning_samples.jsonl --output results/evaluation/update-replayed
```

각 명령은 새로운 출력 폴더만 허용한다. 반복 실행할 때는 출력 폴더 이름을 바꾼다.
`report.json`의 성공 상태와 `full_replay_comparison.matched`를 확인한다.
`result.json`은 이전 참조 및 사건별 결과를, `state.json`은 다음 입력으로 쓸 상태를 담는다.
원본 파일은 그대로 남는다. 프로필에는 합성 기준 시점으로 지정한 만료 시각이 있으므로
현재 운영 시각에 유효한 프로필로 해석하지 않는다.

종료 코드 0은 초기화/갱신 성공, 2는 파일·계약 오류 또는 기존 출력 폴더 사용,
3은 거부/재생 필요/비교 실패다. 실패하면 다음 단계에 적용할 `state.json`을 내보내지 않는다.
최종 위험 점수나 모델 탐지 성능은 이 명령에서 계산하지 않는다.

## 로컬 합성 이력 재현

개인 검증용 `results/evaluation/task-257-replay.py`가 있는 작업 환경에서는 다음처럼
고정 합성 데이터의 4월 1일 → 4월 15일 → 5월 1일을 재현할 수 있다.
이 스크립트와 결과는 Git에서 제외되는 로컬 검증 자료다.

```bash
python results/evaluation/task-257-replay.py --output results/evaluation/profile-update-manual-257-01
```

첫 기준 시점까지 관측된 사용자들의 개인 프로필과 고정 집단의 USER_EQUAL·TRANSACTION
프로필을 갱신한다. 중간 상태를 JSON으로 저장/복원하고 각 결과를 전체 재생과 비교한다.
후속 시점에 처음 등장한 사람을 이전 집단에 소급해서 넣지 않는다. 마지막 PASS 메시지와
최상위 `report.json`의 `compared_profiles == matched_profiles`를 확인한다.
대표 개인 프로필 CLI 출력 16쌍의 바이트 일치도 함께 검증한다.
