# 개인 행동 통계 프로필 생성

`build_personal_profile(request, samples)`는 254의 학습 표본에서 요청한 사용자·통화를 선택하고, 정상 확인된 `INCLUDE` 구매의 금액·구매 빈도·결제 시도 빈도·시간대·요일 분포를 생성합니다. 외부 API, DB, 현재 시각을 읽지 않습니다. [통계 수식과 상태](profile-statistics.md)를 적용하며 위험 점수·차단 여부는 계산하지 않습니다.

## 입력과 결과

```python
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.personal_profiles import build_personal_profile

request = PersonalProfileRequest(
    user_id="synthetic-user", currency="KRW",
    reference={"scope": "PERSONAL", "user_id": "synthetic-user",
               "profile_id": "personal-synthetic-user-KRW", "version": "profile-v1"},
    state_version="statistics-v1", origin="SYNTHETIC", source_reference="your-observable-source",
    as_of="2026-05-01T00:00:00+09:00",
    generated_at="2026-05-01T00:00:01+09:00", valid_until="2026-05-02T00:00:00+09:00",
)
# samples에는 실제 LearningSample 객체를 전달합니다. source_reference도 표본과 맞춥니다.
snapshot = build_personal_profile(request, samples)
```

기본 설정은 최근 **7일**, 장기 **90일**, Feature별 최소 **5개**입니다. 이는 조정 가능한 초기 기술 통계 설정이며 실거래에서 검증된 탐지 임계값이 아닙니다. `request.config`에 `PersonalProfileConfig`를 전달해 변경할 수 있습니다. 최근 범위가 장기 범위보다 길면 거부합니다.

각 범위는 구매 발생 시각 기준 `[as_of - 기간, as_of)`입니다. 프로필에 사용하는 모든 표본의 `eligibility.as_of`는 요청 기준 시각과 같아야 합니다. 다른 시점의 적격 판단을 섞지 않으며, 기준 시각을 바꾸려면 먼저 원천 이력을 254 재생 경로로 다시 구성해야 합니다. 과거 Feature와 이후 정상 확인의 시각 구분은 그대로 유지합니다.

결과에는 다음이 들어갑니다.

- `profile`: 기존 `BehaviorProfile`의 개인 참조, 통화, 출처, 기준·생성·만료 시각, 장기 Feature별 관측 메타데이터
- `recent`, `long_term`: 범위, 반영·보류·제외 구매 수, 9개 Feature별 통계와 유효 표본 수·결측 사유·최근성
- `config`, `config_sha256`, `algorithm_version`: 실제 설정과 버전. 설정 버전 문자열이 같아도 내용이 다르면 설정 해시는 달라집니다.
- `input_sha256`: 사용자·통화 선택 및 장기 범위 필터링 후 보존한 표본의 내용 해시
- `state`: 프로필 참조·상태 버전과 장기 범위 안의 `retained_samples`

`profile.observations`는 장기 통계의 관측 메타데이터입니다. 개인 통계 자체는 `recent`/`long_term`에 있으므로 메타데이터만 저장해서는 분포를 복원할 수 없습니다. 현재 산출물을 Cold의 단일 중심/척도로 자동 변환하거나 개인화 점수 경로에 연결하지 않습니다.

## 갱신을 위한 보존 범위

정상 확인 표본뿐 아니라 `DEFER`·`EXCLUDE` 표본도 장기 범위 안에서 보관합니다. 각 표본에는 구매·주문 참조, 당시 Feature, 원시 수집 범위와 후속 확인 참조가 남습니다. 따라서 JSON을 복원한 후 **같은 기준 시각과 설정에서** 중앙값·분위수를 정확히 다시 계산할 수 있습니다. 기존 253의 `ProfileLearningState`는 판단 기록용 기본 형식이고, `PersonalStatisticsState`는 통계 재계산에 필요한 관측값을 보존하는 별도 형식입니다.

장기 범위 밖의 표본은 보관하지 않습니다. 과거로 기준 시각을 이동하거나 범위를 늘리는 경우, 그 이전의 정정이나 새 후속 확인을 반영하는 경우에는 원천 기록에서 재생해야 합니다. 이 저장 범위만으로 영구 원장이나 DB를 대체하지 않습니다. 실제 갱신·철회·버전 충돌 처리는 후속 Task 범위입니다.

동일 구매의 같은 표본은 한 번만 반영하고, 같은 구매의 서로 다른 내용은 명시적인 정정 재생이 필요하므로 오류로 거부합니다. Feature 배열·시도 ID 순서와 동일 시각의 시간대 표현을 정규화하며, 표본 입력 순서를 바꿔도 결과가 같습니다.

## 완료 기준별 직접 검증

가상환경을 활성화하고 저장소의 `ai` 폴더에서 실행합니다.

```bash
python -m pytest tests/learning/test_profile_statistics.py tests/learning/test_personal_profiles.py tests/learning/test_profile_export.py -v --tb=short
```

예상 결과: **29 passed**. `PASSED`는 예상 결과와 일치했다는 뜻이며, 잘못된 입력 거부 테스트에서는 오류를 올바르게 거부해야 통과합니다.

| 완료 기준 | 선택 실행 (`python -m pytest tests/learning/test_personal_profiles.py` 뒤에 추가) | 확인 내용 |
| --- | --- | --- |
| 1. 사용자·통화·구매 습관 분리 | `-k 'isolated or habits or frequency' -v --tb=short` | 고액·야간·주말·다중 시간대 분포와 정상 학습 대상 구분 |
| 2. 빈 이력·표본 부족·동일 값·자정 | `-k 'empty or midnight or missing' -v --tb=short` | 상태, 분산 0, 순환 경계, 미수집과 실제 0 구분 |
| 3. 범위·최근성·출처·버전 | `-k 'boundaries or reference' -v --tb=short` | 최근/장기 구간의 포함 경계, 관측 시각과 버전 연결 |
| 4. 재현성·JSON 상태 | `-k 'reproducible' -v --tb=short` | 순서·중복·시간대 표현 정규화와 복원 후 재계산 |

파일 내보내기와 기존 합성 데이터 실행은 [개인 프로필 내보내기](personal-profile-export.md)에 설명합니다. Draft MR의 기존 체크박스는 유지하며 사용자가 직접 확인한 뒤 체크합니다.
