# 제공자 프로필 생성·전체 재생 데이터 계약

관련 Task: S15P21A207-299, S15P21A207-302. 모델은 `copenhagen_fds.contracts.provider_profiles`에 있다. 프로필 계산 입력과 결과를 정의하며 DB 조회·원자적 저장이나 호출 서버를 구현하지 않는다. 초기 생성 실행은 `provider-profile-creation.md`를 참고한다. 후속 합의에 따라 프로필 저장소는 AI가 소유하며, 아래 전체 계산 DTO를 Core가 영구 보관하거나 직접 조립하도록 요구하는 외부 계약은 아니다.

## 초기 생성

`ProviderProfileCreateRequest`는 `CREATE_PERSONAL_PROFILE` 또는 `CREATE_POPULATION_PROFILE`을 받는다. `profile`은 각각 기존 `PersonalProfileRequest` 또는 `PopulationProfileRequest`이며, `policy`는 학습 편입 정책이다. 호출 메타데이터와 `configuration` 참조도 필수다. 설정 해시는 `personalization_hash((profile.config, policy))`이다.

개인은 명시한 사용자·통화, 집단은 명시한 cohort와 회원 목록을 사용한다. 입력에 없는 회원을 추정하지 않는다. 다른 사용자/집단 회원, 다른 통화·출처, 미래의 피드백, 중복 구매 관측은 거절한다. 생성 시각부터 요청 제출 시각까지 순서가 맞아야 하며 제출 시점에 이미 만료된 프로필을 생성하도록 요청할 수 없다. 기존 집단 생성기가 구성원을 정렬하므로 응답 연결은 순서만 다른 목록을 동등하게 취급하며 구성 버전·정의·회원 자체는 그대로 대조한다.

빈 전체 관측 `[]`도 유효하다. 반환 상태의 `EMPTY`나 `INSUFFICIENT_SAMPLES`는 자료 부족이지 정상 패턴 학습 완료가 아니다. `CREATED`는 상태를 계산했다는 뜻이다.

## 전체 관측 재생

초기 생성과 갱신 모두 다음 `replay`를 필수로 받는다.

| 필드 | 계약 |
| --- | --- |
| `mode` | 반드시 명시한 `FULL_REPLAY`. 증분 이벤트 API가 아니다. |
| `omitted_purchase` | 반드시 명시한 `REMOVE`. 이전 상태에 있고 새 목록에 없는 구매는 제거 대상이다. |
| `complete_from` | 새 기준 시각에서 유효 통계 설정의 `long_term_days`를 뺀 시각 |
| `complete_until` | 새로운 학습 `as_of` |
| `observations` | 해당 범위 전체의 `PolicyObservation` 목록. 빈 목록도 명시한다. 각 구매는 한 번만 포함한다. |

관측의 구매 발생 범위는 `[complete_from, complete_until)`이다. 각 관측의 학습 적격 판정은 정확히 `complete_until` 기준이어야 한다. 과거 피처 계산 시각은 그대로 유지한다. 관측에 붙은 피드백도 기준 시각에 이용 가능해야 한다.

예를 들어 기존 보관 구매가 A·B·C인데 갱신 목록에 D만 보내면, 이는 D 한 건 추가가 아니라 **A·B·C 제거 후 D만 보관하겠다는 전체 재생 요청**이다. A·B·C·D를 보존하려면 네 구매의 최신 관측을 모두 보내야 한다.

계산 모듈은 명시한 시간 범위가 올바르고 각 행이 범위 안에 있다는 검증까지 수행한다. 실제 전체 관측을 조회했는지, 누락이 의도된 삭제인지 확인하는 책임은 상위 AI 저장·자료 조립 계층에 있다.

## 갱신 요청과 동시성

`ProviderProfileUpdateRequest`는 `UPDATE_PROFILE`, 이전 전체 `ProfilePolicyState`, 기존 `ProfilePolicyUpdateRequest`, 전체 `replay`, 호출/설정 참조를 받는다. 관련 Task S15P21A207-303의 실제 실행과 상태별 검증 예제는 `provider-profile-update.md`를 참고한다.

다음 세 기대값은 **요청에 함께 제공한 이전 상태**와 같아야 한다.

1. `update.expected_policy_state_version`: 최상위 정책 상태 버전
2. `update.update.expected_state_version`: candidate 갱신 상태 버전
3. `update.update.expected_profile_reference`: candidate 프로필 참조

기대값 불일치는 계산 전에 `INVALID_CONTRACT`로 거절한다. 일치하더라도 DB의 현재 버전과 같다는 보장은 아니다. AI 저장 계층이 실제 저장 시점에 DB의 이전 버전을 원자적으로 비교하고 일치할 때만 새 상태를 저장해야 한다. 그 사이 다른 갱신이 저장됐다면 재조회 후 다시 계산한다. 계산 결과가 `UPDATED`라는 이유만으로 덮어쓰면 안 된다.

`changes_complete_from`은 이전 `as_of` 이하, `changes_complete_until`은 새 `as_of`와 같아야 한다. 이 변경 지식 범위와 `replay`의 전체 구매 보관 범위는 별개다. 기준 시각은 역행할 수 없고 다른 scope의 설정을 섞을 수 없다. 구성원이 바뀐 cohort는 별도 초기 재구축이 필요하다. `events`와 전체 관측 재생을 섞을 수 없다. `current_purchase_request_id`가 있다면 그 구매를 학습 관측에 포함할 수 없다.

집단 갱신에서도 회원 목록의 순서만 다른 경우는 동등하게 처리하며, 실제 구성 변경은 허용하지 않는다. 반환된 집단 상태의 구성 근거도 이전 상태와 대조한다.

갱신의 유효 설정은 요청 override가 있으면 그것, 없으면 이전 상태의 설정이다. 설정 참조 해시는 이 **유효 통계 설정과 편입 정책**을 함께 묶는다. 서버는 별도 신뢰 설정과 대조해야 한다.

## 응답과 저장 책임

`ProviderProfileCreateResponse`는 계산한 전체 `state`, `ProviderProfileUpdateResponse`는 기존 `ProfilePolicyUpdateResult`를 담은 `result`를 반환한다. 둘 다 원래 `call`, 전체 `request_sha256`, `completed_at`, `storage_status: NOT_PERSISTED`를 포함한다.

프로필 응답은 **권한 있는 AI 내부 서비스·저장 계층용 데이터**다. 평가 요약 응답과 달리 후속 재생에 필요한 관측·후보 상태·게시 상태·학습 사유를 의도적으로 포함한다. 그대로 모바일/UI에 보내는 DTO가 아니다. 접근 권한·보관 기간·암호화는 서비스 담당과 합의해야 한다.

| 갱신 결과 | 의미 |
| --- | --- |
| `UPDATED` | 새 버전과 상태가 계산됨. AI 저장 계층의 저장 전이다. |
| `UNCHANGED` | 정책 상태와 공개 프로필 버전이 유지됨. |
| `REJECTED` | 계산을 거부한 `issue`. 부분 성공 상태나 부분 이벤트 목록을 반환하지 않는다. |
| `REPLAY_REQUIRED` | 재구축/더 완전한 이력이 필요한 `issue`. 새 상태가 없다. |

`candidate_events`는 후보 데이터 적용/중복/보류 결과다. `transitions`는 관측의 편입·철회·보류·제외·삭제 이유다. 실제 학습 판정은 `state.evaluation.decisions`에서 확인한다. `UPDATED`가 모든 관측의 학습 편입을 의미하지 않는다. 낮은 FDS 점수나 결제 성공을 정상 확정 피드백으로 바꾸지 않는다.

디코딩 뒤 `response.validate_for_request(request)`로 요청 해시·소유자·출처·시각·유효 설정·관측 내용 및 이전 버전을 연결한다. 상태의 자기 일관성/연결 검증과 저장소 진위·동시성 검증은 별개다.

## 직접 검증

`ai/`에서 실행한다. 관련 Task: S15P21A207-299.

```bash
python -m pytest tests/provider/profiles -v --tb=short
```

개인·집단·빈 프로필 생성과 JSON 왕복, 버전 갱신, 전체 빈 재생에 따른 기존 관측 제거, 다른 사용자·누락·부분 범위·미래 피드백·잘못된 버전·시간 역행 거부가 모두 `PASSED`여야 한다. 기존 입력 상태는 바뀌지 않으며 DB 저장은 수행하지 않는다.
