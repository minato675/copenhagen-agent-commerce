# 학습 반영 정책 직접 검증

저장소의 `ai/`에서 가상환경을 활성화한 뒤 실행한다. 테스트 파일을 `python 파일명.py`로
열지 않고 `python -m pytest`로 실행한다. 외부 API 키나 서비스 DB는 필요하지 않다.

## 완료 기준별 검증

| 기준 | 실행 명령 | 예상 결과 |
|---|---|---|
| 반복 의심 거래·반영 상한·보류 이유 | `python -m pytest tests/learning/policy/test_contracts_and_reference.py tests/learning/policy/test_admission.py -v --tb=short` | 24 passed |
| 지연 정상 확인·사기 철회·정정 재생 일치 | `python -m pytest tests/learning/policy/test_policy_updates.py tests/learning/policy/test_replay_adapter.py -v --tb=short` | 16 passed |
| 정상 습관 변화·최근/장기 근거·정책 버전 | `python -m pytest tests/learning/policy/test_admission.py -v --tb=short` | 12 passed |
| 미래 근거·정답 분리·동일 입력 재현 | `python -m pytest tests/learning/policy/test_admission.py tests/learning/policy/test_replay_adapter.py tests/learning/policy/test_export.py -v --tb=short` | 21 passed |

전체 258 검증은 다음 명령으로 한 번에 실행한다. 예상 결과는 **45 passed**다.

```bash
python -m pytest tests/learning/policy -v --tb=short
```

기존 기능 전체를 함께 확인하려면 `python -m pytest -q --tb=short`를 실행한다.

## 실제 합성 데이터에서 정책 적용

확인 시각까지 포함한 정책 관측 파일을 만든다. 이 명령은 고정 합성 데이터의 관측 파일만
읽으며 정답/페르소나 파일을 열지 않는다.

```bash
python -m copenhagen_fds.profile_policy_export observe data/synthetic/fds-behavior-v1/stream --as-of "2026-05-01T00:00:00+09:00" --output results/evaluation/policy-evidence-manual-258-01
```

같은 기준 시점의 후보 스냅샷에 적용한다. 256의 집단 결과가 있는 환경에서는 다음처럼
실행할 수 있다. 여러 후보가 담긴 JSONL이면 `--profile-id`로 하나를 선택한다.

```bash
python -m copenhagen_fds.profile_policy_export init results/evaluation/population-v1-stream-256-user_equal/population_profiles.jsonl results/evaluation/policy-evidence-manual-258-01/observations.jsonl --output results/evaluation/policy-initial-manual-258-01
```

출력의 의미는 다음과 같다.

- `report.json`: 정책별 반영/보류/제외 수, 보류한 정상 확인 수, 최근/장기 반영 수.
- `policy_evaluation.json`: 각 구매의 구체적인 이유·확인 근거·시각·변화 지지 구매·반영 상한.
- `published_profile.json`: 정책을 적용한 개인/집단 통계.
- `state.json`: 후보 수정 상태·공개 통계·확인 근거·정책 판단을 함께 보관하는 다음 갱신 입력.

`policy_held_confirmed`는 정상으로 확인됐지만 정책이 보류한 수다. 이 값은 사기 건수가
아니며, 기존 통계의 `deferred_count`와도 구분한다. 샘플 지원 상태 READY는 탐지 정확도가 아니다.

## 다음 기준 시점으로 갱신

새 관측 파일은 대상 범위의 완전한 재생이어야 한다. 요청 JSON은 다음 필드를 가진다.

```json
{
  "expected_policy_state_version": "현재 state.json의 state_version",
  "update": {
    "expected_state_version": "현재 candidate.state_version",
    "expected_profile_reference": {"profile_id": "후보 ID", "version": "후보 버전", "scope": "PERSONAL", "user_id": "사용자 ID"},
    "as_of": "2026-05-02T00:00:00+09:00",
    "generated_at": "2026-05-02T00:00:01+09:00",
    "valid_until": "2026-05-03T00:00:00+09:00",
    "changes_complete_from": "2026-05-01T00:00:00+09:00",
    "changes_complete_until": "2026-05-02T00:00:00+09:00"
  }
}
```

참조 값은 실제 상태에서 가져온다. 집단이면 POPULATION과 cohort_id를 사용한다.
정책 설정 변경은 최상위 `config`로 전달한다. 이벤트 일부를 `update.events`로 섞지 않는다.

```bash
python -m copenhagen_fds.profile_policy_export update state.json request.json observations.jsonl --output results/evaluation/policy-next-manual-258-01
```

`changes.json`에서 ADMITTED/WITHDRAWN 등 변경과 원인을 확인한다. 성공은 종료 코드 0,
파일/계약 오류·이미 있는 출력 폴더는 2, 버전 거부·재생 필요는 3이다. 실패에는 새 상태나
공개 프로필 파일이 생성되지 않는다. 원본은 덮어쓰지 않으므로 재실행 시 출력 이름을 바꾼다.

일별 상한은 확인 가용 시각으로 재생한 **논리적 반영 날짜**의 건수 상한이다. 초기화나
누락 이력의 복원 시 한 함수 호출에서 바뀌는 전체 표본 수·통계 변화율의 상한은 아니다.
통계 변화율을 직접 제한하거나 운영 처리량을 검증하는 기능은 제공하지 않는다.

## 로컬 단계별 재현 자료

Git에서 제외한 `results/evaluation/task-258-replay.py`가 있는 환경에서는 아래 명령으로
4월 15일 → 5월 1일 갱신과 전체 재생을 비교한다.

```bash
python results/evaluation/task-258-replay.py --output results/evaluation/profile-policy-manual-258-01
```

두 합성 데이터의 집단/대표 개인 결과 일치, 사용자별 일별 상한, 대표 CLI 출력 20쌍의
바이트 일치를 확인한다. 새로 관측된 사용자는 새 개인 프로필로 처리한다. 시나리오 분류는
모든 정책 계산이 끝난 뒤에만 결과 보고에 사용한다. 사기 정확도나 최종 위험 점수는 계산하지 않는다.
