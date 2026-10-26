# 제공자 개인·집단 프로필 초기 생성

관련 Task: S15P21A207-302. `provider_runtime.profile_creation.create_profile_handler`를 두 초기 생성 작업에 등록한다. 기존 프로필 갱신과 FDS 평가 호출은 별개다.

```python
from copenhagen_fds.provider_runtime.profile_creation import create_profile_handler

operation_handlers = {
    "CREATE_PERSONAL_PROFILE": create_profile_handler,
    "CREATE_POPULATION_PROFILE": create_profile_handler,
}
# 이 매핑을 provider-runtime.md의 ProviderRuntime(handlers=...)에 주입한다.
# 인증·권한·신뢰 설정·입력/응답 한도와 시간 예산은 상위 서비스가 설정한다.
```

## 계산과 응답

1. `ProviderBoundary`가 서비스 인증, 작업·사용자·프로필·집단 권한, 전체 재생 DTO와 신뢰 설정을 검사한다. 관측이 없는 집단 구성원도 권한 범위에 있어야 한다.
2. handler가 제출 시각과 실행 시각, 유효기간을 확인한다. 미래 제출이나 이미 만료된 요청은 `INVALID_CONTRACT`다.
3. `build_profile_candidate`가 관측의 학습 표본으로 기존 `build_personal_profile` 또는 `build_population_profile`을 호출한다. 상위 계약에서 다른 사용자·통화·범위의 관측을 거절하므로 원래 통계 함수의 필터링으로 조용히 누락시키지 않는다.
4. `initialize_profile_policy`가 정상 확인 근거와 편입 정책을 재생해 후보와 게시 프로필을 구분한다. `project_profile_create_response`가 원래 요청 해시·참조·버전·통계 설정·정책·관측을 응답과 연결한다.

`state.candidate.snapshot`은 정책 적용 전 후보 통계이고 `state.published`는 후속 FDS 평가용 프로필이다. `state.evaluation.decisions`와 `state.observations`에서 보류·제외 사유와 확인 근거를 조회한다. 게시 프로필은 기존 규칙대로 `-admitted` 식별자와 별도의 내용 기반 버전을 가진다. 빈 이력은 `EMPTY`, 부족 표본은 `INSUFFICIENT_SAMPLES`를 유지한다. `CREATED`는 충분한 학습이나 저장 성공을 뜻하지 않는다.

정상 확인된 급격한 변화도 정책상 `DEFER / ABRUPT_CHANGE`이면 후보에 남고 게시 통계에는 편입되지 않는다. 현재 평가 거래의 `CURRENT_PURCHASE` 판정, 확인되지 않은 거래, 사기 확인, 의심·미검증 피드백과 누락된 확인 근거도 그대로 보존한다. 결제 성공이나 낮은 FDS 점수로 정상 확인 라벨을 만들지 않는다.

같은 자료·통계 설정·편입 정책은 관측/집단 구성원의 순서와 무관하게 같은 프로필 상태와 버전을 만든다. 응답의 `request_sha256`은 제출된 전체 요청에 연결되므로 배열 순서·호출 메타데이터가 달라지면 달라질 수 있다. 구성 버전·집단 정의·구성원 자체의 변경은 순서 변경과 다르게 검사한다.

## 실행·저장 경계

- 이 모듈은 제공된 `PolicyObservation`을 계산한다. 원천 이력 조회·피처 추출·적격 표본 조립은 기존 학습 재생과 상위 AI 자료 조립 계층의 책임이다. `FULL_REPLAY` 범위 선언은 필수지만 실제 DB에 누락 행이 없는지 증명하지는 않는다.
- 원문·프로필·수집 상태의 저장소 소유는 AI다. 현재 계산 응답은 `NOT_PERSISTED`이며 AI 저장 계층의 최초 중복 처리·원자적 저장이나 Core의 참조 기반 외부 요청 조립을 구현하지 않는다. 응답에는 학습 근거가 포함되므로 권한 있는 내부 서비스용이며 UI에 그대로 보내지 않는다.
- GMS/FDS 평가·DB·파일 저장을 호출하지 않는다. `resource_factory=None`으로 실행할 수 있다. 공용 `GmsResourceFactory`도 초기 생성 작업에는 GMS 자원을 만들지 않는다.
- 입력의 유효기간을 연장하지 않는다. 계산 중 만료돼도 `INVALID_CONTRACT`이며 부분 상태를 성공으로 반환하지 않는다. 내부 계산 예외는 기존 Runtime의 `INTERNAL_ERROR`, 예산 소진은 `DEADLINE_EXCEEDED`, 취소는 `CancelledError`다.
- 통계·정책은 동기 CPU 계산이다. 단계 사이에서 이벤트 루프에 제어를 넘기고 공유 시간 예산/취소를 검사하며, 한 단계 내부를 강제 중단하지는 못한다. 실제 규모의 입력 제한과 처리 시간은 운영 실측으로 결정해야 한다.

## 직접 검증

저장소의 `ai/` 폴더에서 Git Bash 가상환경을 활성화한 뒤 실행한다. PowerShell에서는 `python` 대신 `.\.venv\Scripts\python.exe`를 사용한다.

```bash
source .venv/Scripts/activate
python -m pytest tests/provider/runtime/test_profile_candidate.py tests/provider/runtime/test_profile_projection.py tests/provider/runtime/profile_creation tests/provider/profiles/test_creation_membership.py -v --tb=short
```

302번 신규 검증은 **59 passed**가 예상된다. 테스트는 합성 관측과 고정된 가상 시계로 기존 통계·정책 함수를 실제 실행한다. 모델이나 네트워크는 호출하지 않는다.

| 완료 기준 | 테스트 (`ai/tests/` 기준) | 확인 내용 |
| --- | --- | --- |
| 기존 생성/정책 일치, 후보·게시 참조/버전 | `provider/runtime/test_profile_candidate.py` 5개, `test_profile_projection.py` 9개, `profile_creation/test_execution.py` 6개 | 기존 결과 일치·요청 연결·입력 불변·실제 실행·순서 재현 |
| 대상·가용 시각·완전성·집단 권한 | `provider/runtime/profile_creation/test_validation.py` 17개, `provider/profiles/test_creation_membership.py` 4개 | 잘못된 입력/권한은 계산 전 거절, 구성원의 순서와 실제 구성 변경 구분 |
| 부족·보류·제외와 부작용 분리 | `provider/runtime/profile_creation/test_admission.py` 8개, `test_interruption.py` 9개 | 부족/원래 사유 보존, GMS 없는 계산, 실패·취소·만료에 부분 성공 없음 |
| 요청/응답 직접 확인·회귀 | `provider/runtime/profile_creation/test_examples.py` 1개 및 아래 회귀 명령 | 개인·집단·소량·빈 이력·급변 보류의 실행 예제 |

```bash
python -m pytest tests/provider/runtime/profile_creation/test_examples.py --provider-profile-examples results/evaluation/provider-profile-302 -v --tb=short
```

**1 passed** 후 `results/evaluation/provider-profile-302/`에 요청/응답 5쌍과 `index.json`이 생성된다. Git 제외 경로다. 인증 자격 증명은 내보내지 않는다.

| 응답 파일 | `state.published.long_term.status` | 게시 `included_count` | 추가 확인 |
| --- | --- | --- | --- |
| `personal-response.json` | `READY` | 2 | 예제 통계 기준 `min_samples=2` |
| `population-response.json` | `READY` | 2 | 서로 다른 사용자 2명과 명시한 집단 구성 |
| `sparse-response.json` | `INSUFFICIENT_SAMPLES` | 1 | 정상 표본이 있어도 충분하지 않음 |
| `empty-response.json` | `EMPTY` | 0 | 빈 상태도 생성되지만 학습 완료 아님 |
| `held-response.json` | `INSUFFICIENT_SAMPLES` | 3 | 후보는 4건, 급격한 변화 1건은 `ABRUPT_CHANGE` 보류 |

이 예제의 2026년 5월 기준 시각·완료 시각은 재현용 가상 시계다. 실서비스에 현재 요청으로 전송할 자료나 운영 최소 표본 기준이 아니다. 저장 완료도 아니다.

기존 제공자와 프로필 회귀:

```bash
python -m pytest tests/provider tests/learning/test_personal_profiles.py tests/learning/test_population_profiles.py tests/learning/test_profile_statistics.py tests/learning/policy -q --tb=short
```

이번 검증 결과는 **324 passed**다. 저장소 전체 테스트 결과나 실제 Core/Pay HTTP 호출·저장소 연동·GMS 지연·실거래 탐지 품질 검증은 아니다. Draft MR 체크박스는 사용자가 직접 검증한 뒤 체크한다.
