# 정책을 적용한 프로필 갱신

`initialize_profile_policy(snapshot, observations, config)`는 완전한 기존 통계 스냅샷에
정책을 적용한다. `update_profile_policy(state, request, observations)`는 새 기준 시점의
완전한 관측/확인 목록을 257의 수정 이력에 반영한 뒤 정책을 재생한다.

정책 상태는 다음 내용을 함께 보관한다.

- `candidate`: 원래 학습 적격 판정에 따른 모든 표본과 257의 수정 이력. 정책 적용 전 후보용이다.
- `published`: 정책이 허용한 정상 표본으로 만든 개인·집단 통계. 후속 평가에 사용할 대상이다.
- `observations`: 보관 중인 원래 표본과 당시 이용 가능한 실제 확인 근거.
- `evaluation`: 반영/보류/제외 이유, 정책·설정 버전, 시점, 최근/장기 기준, 변화 지지 근거.

두 프로필의 식별자와 버전은 구분된다. `published`는 기존 개인·집단 통계 계약을 그대로
사용하며, 정책 버전과 근거를 담은 바깥 상태와 함께 취급해야 한다.

정책 때문에 보류한 NORMAL_CONFIRMED 표본은 `published`의 통계 표본에서 제외한다.
이 표본의 원래 판정을 DEFER로 위조하지 않으므로, `published.long_term.deferred_count`는
원래 학습 판정의 DEFER 수다. **정책 보류 수는 `evaluation.decisions`에서 따로 계산**한다.
의심/미확인/사기 등 원래 비정상 학습 상태의 표본은 기존 통계의 보류·제외 집계에 남는다.

갱신 시 `expected_policy_state_version`과 내부 요청의 후보 상태·프로필 기대 버전을 모두
확인한다. 실패는 부분 적용 없이 반환하고 기존 객체와 이전 프로필 참조를 보존한다.
설정 변경은 설정 해시와 공개 프로필 버전에 반영하며, 같은 정보·시점·설정으로 재호출하면
UNCHANGED다. 원자적 비교·저장과 트랜잭션은 BE의 책임이다.

변경 내역에는 ADMITTED, WITHDRAWN, REPLACED, HELD, EXCLUDED, REMOVED와 원인을
기록한다. 사기 확인뿐 아니라 원천 삭제, 보관 기간 만료, 정책 재평가에 따른 철회도 구분한다.
정정은 현재 이용 가능한 최종 정보로 보관 범위를 다시 재생하므로 과거에 잘못 반영된 표본의
영향이 후속 후보의 기준에 남지 않는다. 영구적인 과거 판단 기록은 별도 저장 책임이다.

`observations`는 **완전한 새 학습 재생**이어야 한다. 누락된 구매는 원천 삭제로 해석된다.
변경 이력이 불완전하거나 더 긴 보관 범위가 필요하면 기존 257의 REPLAY_REQUIRED 규칙을 따른다.

`policy_observations_from_replay`는 기존 학습 재생 입력에서 실제 선택된 확인 근거를
연결한다. 학습 표본의 `as_of`를 확인 가용 시각으로 추정하지 않는다. 합성 데이터에도 같은
연결을 사용하며 정답/페르소나를 입력으로 받지 않는다.

검증:

```bash
python -m pytest tests/learning/policy/test_policy_updates.py tests/learning/policy/test_replay_adapter.py -v --tb=short
```
