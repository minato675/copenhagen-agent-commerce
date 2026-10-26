# 제공자 프로필 갱신과 충돌 처리

관련 Task: S15P21A207-303. `provider_runtime.profile_update.update_profile_handler`를 `UPDATE_PROFILE`에 등록한다. 개인·집단의 기존 정책 상태와 전체 관측을 받아 `update_profile_policy`를 한 번 실행한다.

```python
from copenhagen_fds.provider_runtime.profile_update import update_profile_handler

operation_handlers = {"UPDATE_PROFILE": update_profile_handler}
# provider-runtime.md의 ProviderRuntime(handlers=...)에 다른 작업과 함께 등록한다.
```

## 갱신 입력과 결과

`ProviderBoundary`가 서비스 인증·작업 권한·이전 상태와 재생 자료의 대상 권한·입력 한도·신뢰 설정을 검사한다. `state`에는 이전 후보/게시 상태·수정 이력·학습 근거가 들어간다. `update`의 기대 정책 상태 버전, 후보 상태 버전, 후보 프로필 참조는 함께 제출한 이전 상태와 같아야 한다. 출력은 내부 학습 근거를 포함하므로 권한 있는 AI 서비스용이며 UI용 DTO가 아니다.

`replay.mode=FULL_REPLAY`, `omitted_purchase=REMOVE`와 전체 보관 범위·관측 목록을 명시한다. `[as_of - long_term_days, as_of)`의 전체 구매 관측이며 각 적격 판정의 기준 시각은 새 `as_of`다. 변경 정보 범위는 이전 기준 시각부터 새 기준 시각까지 이어져야 한다. 미래의 확인 근거·다른 사용자/통화·현재 평가 거래·중복 구매 행·혼합 증분 이벤트를 허용하지 않는다.

이전 거래 A·B·C 중 A·B만 보내면 C를 삭제한다. A·B·C를 유지하며 D를 추가하려면 A·B·C·D의 최신 관측을 모두 보내야 한다. 빈 전체 재생은 이전 관측 전부의 제거를 뜻한다. 선언된 범위의 실제 누락 여부는 계산 모듈이 증명할 수 없으며 상위 AI 이력 조립 계층이 확인해야 한다.

| 결과 | 의미 |
| --- | --- |
| `UPDATED` | 후보/게시 정책 상태 또는 기준 시각·설정이 바뀌어 새 상태를 계산함. 모든 거래의 학습 편입을 뜻하지 않음 |
| `UNCHANGED` | 같은 자료·시각·설정으로 재처리해 상태와 버전 유지 |
| `REJECTED` | 기존 갱신기가 반환한 거부 사유 유지. 예: 생성 시각 역행의 `TIME_REGRESSION` |
| `REPLAY_REQUIRED` | 이전 보관 상태만으로 처리할 수 없어 재구축 필요. 예: `RETENTION_GAP`, `CURRENT_PURCHASE_IN_STATE` |

`result.candidate_events`는 수정 이력의 적용 결과, `result.transitions`는 학습 편입·보류·교체·철회·제외/삭제의 전이, `result.state.evaluation.decisions`는 새 기준에서의 학습 판단이다. 늦게 도착한 정상 확인을 편입하고 이후 사기 정정은 철회한다. 급격한 정상 변화는 정책상 보류될 수 있으며 후속 근거가 충분해야 편입한다. 낮은 FDS 점수나 결제 성공을 정상 확인 라벨로 바꾸지 않는다.

`project_profile_update_response`는 요청 해시·이전 상태 버전·이전 게시 참조·새 시각·유효 설정·전체 관측을 연결한다. 집단의 구성원 순서만 다른 경우는 허용하지만 구성 버전·정의·회원이 바뀐 갱신은 별도 초기 재구축이 필요하다. 반환된 집단 상태도 원래 구성 근거와 대조한다. 실패 결과에는 부분 상태·부분 전이·부분 적용 이벤트가 없다.

## 거절·재처리·저장 경계

- **계약 단계와 계산 결과를 구분한다.** 오래된 기대 버전, 부분 범위 선언, 증분 모드, 중복 구매 행 등은 기존 299번 계약에 따라 계산 전 `ProviderError.code=INVALID_CONTRACT`로 거절한다. `REJECTED`나 `REPLAY_REQUIRED`는 계약을 통과한 요청을 기존 갱신기가 처리한 결과다. 잘못된 요청을 정상 DTO나 가짜 계산 결과로 바꾸지 않는다.
- 같은 **이전 상태**로 같은 요청을 다시 보내면 같은 계산 결과가 나오며 `UPDATED`가 반복될 수 있다. 첫 결과를 저장한 **새 상태**와 새 기대 버전으로 동일 자료를 재생해야 `UNCHANGED`가 된다. 현재 모듈에는 저장소 중복 처리나 멱등 결과 캐시가 없다.
- 후속 합의에 따라 프로필 저장소는 **AI 소유**다. 모든 응답은 `NOT_PERSISTED`이며, AI 저장 계층은 계산 전에 읽은 기대 버전을 실제 DB의 현재 버전과 원자적으로 비교한 뒤 후보·게시·정책 상태를 함께 저장해야 한다. 일치하지 않으면 최신 상태를 읽고 다시 계산한다. 메모리 검증만으로 동시 저장 성공을 보장하지 않는다.
- FDS 평가·초기 생성 handler·GMS·DB 저장을 호출하지 않는다. 원래 입력 객체도 변경하지 않는다. 실제 Core/Pay 호출 경로, AI 저장소 연결, 이벤트 수집/중복 처리 구현은 이 계산 handler에 포함되지 않는다.
- 요청 제출 시각부터 새 유효기간 사이에서 실행·완료돼야 한다. 이미 만료된 **이전** 게시 프로필은 새 유효기간으로 재계산할 수 있다. 새 유효기간이 실행 중 만료되면 `INVALID_CONTRACT`, 공유 예산 만료는 `DEADLINE_EXCEEDED`, 내부 계산 예외는 `INTERNAL_ERROR`, 취소는 `CancelledError`다.
- 동기 CPU 재생 전후로 이벤트 루프에 제어를 넘기고 예산·취소를 확인한다. 재생 함수 내부의 강제 중단을 제공하지 않으므로 운영 입력 한도와 처리 시간은 실제 규모로 측정해야 한다.

## 직접 검증

실행 위치는 저장소의 `ai/`다. Git Bash에서 가상환경을 활성화하거나 PowerShell에서 `python` 대신 `.\.venv\Scripts\python.exe`를 사용한다.

```bash
source .venv/Scripts/activate
python -m pytest tests/provider/runtime/test_profile_update_projection.py tests/provider/runtime/profile_update tests/provider/profiles/test_update_membership.py -v --tb=short
```

303번 신규 검증은 **49 passed**가 예상된다. 합성 관측과 가상 시계로 실제 갱신기를 실행한다. 외부 네트워크·GMS·DB 저장은 사용하지 않는다.

| 완료 기준 | 테스트 (`ai/tests/` 기준) | 검증 내용 |
| --- | --- | --- |
| 기존 결과·버전·전이 일치와 원본 유지 | `provider/runtime/test_profile_update_projection.py` 9개, `profile_update/test_execution.py` 5개 | 실제 함수 연결, JSON 왕복·요청 연결, 이전 상태 불변·평가 독립 실행 |
| 전체 재생·버전 충돌·범위/권한 | `provider/runtime/profile_update/test_conflicts.py` 16개, `provider/profiles/test_update_membership.py` 3개 | 계약 오류와 계산 거부/재생 필요 구분, 누락 관측만으로 이전 상태 권한 우회 불가 |
| 지연·정정·원천 철회·재처리·보류 | `provider/runtime/profile_update/test_replay.py` 4개, `test_redelivery.py` 2개, `test_admission.py` 2개 | 새로 만든 프로필과 재생 결과 비교, 철회/교체, 반복 전달과 변경 없음, 정책 보류/해제 |
| 실패 중단과 실행 예제·회귀 | `provider/runtime/profile_update/test_interruption.py` 7개, `test_examples.py` 1개 및 아래 회귀 명령 | 실패 후 부분 상태 없음, 새/이전 유효기간 구분, 예제 직접 확인 |

```bash
python -m pytest tests/provider/runtime/profile_update/test_examples.py --provider-update-examples results/evaluation/provider-update-303 -v --tb=short
```

**1 passed** 후 Git 제외 경로 `results/evaluation/provider-update-303/`에 요청/응답 7쌍과 `index.json`이 생성된다. 자격 증명은 포함하지 않는다.

| 응답 파일 | 확인할 결과 |
| --- | --- |
| `updated-personal-response.json`, `updated-population-response.json` | `result.status=UPDATED`, 새 정상 확인 거래 `ADMITTED` |
| `unchanged-response.json` | `UNCHANGED`, 상태/버전 유지, 빈 전이 |
| `withdrawn-response.json` | `UPDATED`, 기존 두 거래 `WITHDRAWN / OBSERVATION_REMOVED`, 게시 통계 `EMPTY` |
| `replay-required-response.json` | `REPLAY_REQUIRED / RETENTION_GAP`, 새 상태 없음 |
| `rejected-response.json` | `REJECTED / TIME_REGRESSION`, 새 상태 없음 |
| `stale-version-response.json` | `code=INVALID_CONTRACT`인 오류 봉투. 이 요청 예제는 의도적으로 잘못된 기대 버전임 |

예제의 2026년 5월 시각은 재현용 가상 시계다. 현재 실서비스에 전송할 운영 입력이 아니다. 오류 ID는 실행마다 달라질 수 있다.

관련 회귀:

```bash
python -m pytest tests/provider tests/learning/updates tests/learning/policy -q --tb=short
```

이번 결과는 **397 passed in 104.53s**다. 신규 49개를 포함한 관련 회귀이며 저장소 전체 테스트 결과는 아니다. 실제 서비스 호출·원자적 저장·실거래 탐지 품질 검증은 아니다. Draft MR 체크박스는 사용자가 직접 확인한 뒤 체크한다.
