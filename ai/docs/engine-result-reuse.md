# 기록된 FDS 결과의 재사용 검사

`check_engine_result_reuse(record, current, *, context)`는 제공된 기존 통합 결과와
현재 요청을 검증하여 `VALID`, `REEVALUATION_REQUIRED`, `INVALID_LINK`를 반환한다.
`NativeEvaluationResult` / `EngineEvaluationRequest` 객체 또는 JSON을 파싱한 dict를 받는다.
내용 연결 규칙은 [engine-result-connection.md](engine-result-connection.md)를 따른다.

## 사용 방법

```python
import json
from pathlib import Path

from copenhagen_fds.engine_reuse import check_engine_result_reuse

saved_result = json.loads(Path("saved-result.json").read_text(encoding="utf-8"))
current_request = json.loads(Path("current-request.json").read_text(encoding="utf-8"))
context = json.loads(Path("reuse-context.json").read_text(encoding="utf-8"))
assessment = check_engine_result_reuse(saved_result, current_request, context=context)
print(assessment.model_dump_json(indent=2))
```

위 파일명은 호출 예시다. 실제 결과·현재 요청·검사 정보를 호출자가 준비해야 한다.
이 함수는 파일을 읽거나 저장하지 않는다. `context`의 계약은 다음과 같다.

| 필드 | 호출자가 제공할 근거 |
| --- | --- |
| `checked_at` | 검사 시각. 시간대가 있는 ISO 8601 시각이며 행동 기준 시각을 대체하지 않음 |
| `validity.result_sha256` | 저장 결과 전체의 `personalization_hash(saved_result)`와 대조할 해시 |
| `validity.result_valid_until` | 해당 결과에 명시적으로 부여하고 보관한 만료 시각 |
| `validity.risk_policy_sha256` | 원래 요청의 위험 정책 전체에 대한 `personalization_hash(risk_policy)` |
| `validity.policy_valid_from`, `policy_valid_until` | 해당 정책 내용에 명시적으로 부여한 유효 구간 |
| `expected_extraction_model_version`, `expected_comparison_model_version` | 현재 허용하는 실제 응답 모델 버전 |

`EngineReuseContext`와 `EngineResultValidity`는 `copenhagen_fds.contracts.engine_reuse`에 있다.
해시 계산 함수는 `copenhagen_fds.contracts.personalization_inputs.personalization_hash`다.
저장 결과와 현재 요청은 모델로 검증한 내용으로 해시를 계산한다. JSON 문자열 자체를 해시하지 않는다.

기존 통합 결과에는 결과 자체의 만료 시각이 없으므로, 이를 임의로 추가하지 않고 별도의
유효성 정보(`validity`)를 받는다. 검사할 때마다 현재 시각에 TTL을 더해 새 만료를 만들거나,
누락된 정보를 예전 결과에서 추측하여 채우면 안 된다. 별도 정보가 없으면 재평가 필요로 반환한다.
해시는 내용 대조용이며 서명·발급자 인증이 아니다. 실제 저장소의 신뢰성과 기한 부여는 호출자 책임이다.
현재 기대 모델 버전도 예전 결과에서 그대로 복사하여 현재 버전이라고 가정하지 않는다.

## 판정 규칙

| 상태 | 예시 |
| --- | --- |
| `VALID` | 전체 요청이 같고 정상 완료한 결과이며 필요한 버전·기한이 확인되고 모두 유효 |
| `REEVALUATION_REQUIRED` | 새 주문/의도/프로필 버전, 설정 변경, 만료, 정보 부족, 실패·취소 결과 |
| `INVALID_LINK` | 다른 사용자/구매, 동일 참조·버전의 내용 충돌, 잘못된 계약·시각·유효성 정보 연결 |

여러 사유가 있으면 모두 보존하고 연결 오류를 우선한다. 내용 사유는 `connection.reasons`,
시간·상태·정보 부족 사유는 `issues`에 구성 요소와 함께 남는다. 원문·예외 메시지는 복사하지 않는다.
`VALID`일 때만 `reusable=true`이고 `valid_until`에 가장 이른 만료 시각이 들어간다.
거절한 결과는 `valid_until=null`이며, 해당 경계는 각 사유의 `boundary`로 확인할 수 있다.

- 검사 시각은 최종 결과 완료 시각보다 빠를 수 없다. 결과·정책 기한이 다른 결과나 정책에
  연결되어 있거나 유효 구간이 뒤집혀 있으면 연결 오류다.
- 개인/집단 프로필, 집단에서 파생한 기준, 초기 기준 정책의 게시·가용 시각과 만료를 검사한다.
  요청에 함께 제공한 참조는 실제 점수에 사용되지 않았더라도 보수적으로 검사한다.
- GPT의 기한은 원래 위험 정책에 명시된 `intent_max_age_seconds`를 각 응답의 평가 시각에
  적용한다. 응답에 `metadata.valid_until`도 있으면 둘 중 더 이른 시각을 따른다.
- 모든 만료는 **배타적 경계**다. 만료 직전은 유효할 수 있고, 만료 시각과 같거나 이후면 만료다.
- 실제 응답 모델 버전을 현재 기대 버전과 비교한다. 같은 요청 모델 별칭만으로 같다고 판단하지 않는다.
- 의도 버전이 없거나 필요한 기한/연결 정보가 없으면 재평가가 필요하다. 원문이 명시적으로
  없고 의도를 선택 항목으로 둔 정상 완료 결과의 `NOT_RUN` 단계에는 응답 모델/기한을 만들지 않는다.
- `outcome.status=SUCCESS` 및 정상 완료가 필요하다. 취소/시간 초과 후 보관된 정책 결과는 재사용하지 않는다.
- `checked_at`만 흐르는 검사와 새 거래의 행동 기준 시각 변경은 별개다. TTL이 남아 있어도
  행동 기준 시각·이력·평가 ID·trace가 달라지면 새 요청으로 재평가해야 한다.

`VALID`는 **제공된 동일한 과거 평가 스냅샷의 재사용 조건**을 충족했다는 뜻이다.
실제 주문/프로필 최신 상태를 조회한 사실이나 BE 승인·인증·결제 실행 권한을 의미하지 않는다.
검사 중 외부 API/DB 조회, GMS 호출, 점수 재계산, 프로필 갱신은 수행하지 않는다.

## 직접 검증 — S15P21A207-292

가상환경을 활성화하고 `ai/`에서 아래를 실행한다. Python 파일을 직접 실행하는 대신
`python -m pytest`로 실행해야 테스트 결과가 출력된다. 실제 GMS 키는 필요 없다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/reuse -v --tb=short
```

총 96개 항목이 모두 `PASSED`여야 한다. 아래는 완료 기준별 선택 실행 방법이다.
Git Bash에서 경로를 한 번 지정한 뒤 원하는 명령을 실행한다.

```bash
REUSE_TESTS=tests/learning/personalization/replay/engine/analysis/finalization/reuse

# 완료 기준 1: 동일 요청 재사용과 변경 입력의 구분
python -m pytest "$REUSE_TESTS" -v --tb=short -k "exact_successful or content_and_settings or target_identity"

# 완료 기준 2: 내용·버전 변경과 만료 직전/동일/이후
python -m pytest "$REUSE_TESTS" -v --tb=short -k "expiry or explicit_gpt or new_published or published_profiles or content_and_settings or same_requested_alias or invalid_or_unsupported"

# 완료 기준 3: 누락·잘못된 시각·기준 시각 변경·불완전 결과
python -m pytest "$REUSE_TESTS" -v --tb=short -k "absent_validity or missing_intent or invalid_dates or ttl_does_not_move or failed_or_incomplete or late_retained or validity_from_another"

# 완료 기준 4: 무호출·불변성·JSON 재현
python -m pytest "$REUSE_TESTS" -v --tb=short -k "read_only or equivalent_timezone or result_json"
```

선택 실행에서는 나머지 항목이 `deselected`로 표시되는 것이 정상이다. 선택한 항목은 모두 통과해야 한다.
첫 번째 기준은 동일 요청만 `VALID`, 변경 요청은 재평가 또는 연결 오류인지를 확인한다.
두 번째 기준은 변경 이유와 구성 요소별 만료 경계를 확인한다. 세 번째 기준은 누락/오류 이유가 남고
`reusable=false`인지, 네 번째 기준은 JSON 왕복과 호출 전후 입력 불변 및 실행 함수 무호출을 확인한다.
이 테스트는 계약과 제어 흐름 검증이며 실제 사기 탐지 성능이나 서비스 응답 시간 측정이 아니다.
사용자 직접 검증 체크박스는 실행 결과를 확인한 후 사용자가 체크한다.
