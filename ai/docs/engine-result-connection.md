# 기록된 평가 결과의 대상·내용 연결

`compare_engine_result(record, current)`는 기존 `NativeEvaluationResult`와 현재
`EngineEvaluationRequest`를 검증하고 비교한다. 객체 또는 해당 JSON을 파싱한 dict를 받는다.
실제 평가·GMS·DB 조회·프로필 갱신 없이 제공된 스냅샷만 읽는다.

```python
from copenhagen_fds.engine_reuse_connection import compare_engine_result

connection = compare_engine_result(saved_result, current_request)
print(connection.status, connection.reasons)
```

| 상태 | 의미 |
| --- | --- |
| `MATCHED` | 원래 요청과 현재 요청의 전체 내용·버전·기준 시각·추적 식별자가 동일 |
| `REEVALUATION_REQUIRED` | 같은 구매의 주문/의도 버전, 행동 기준, 이력, 프로필, 기준, 설정 등이 변경 |
| `INVALID_LINK` | 다른 사용자·구매, 같은 주문 참조/의도 버전의 내용 충돌, 유효하지 않은 결과/현재 요청 |

복수 원인은 모두 기록하고 연결 오류를 우선한다. 동일 참조는 주문의 snapshot_id·version·
snapshot_hash 전체가 같은 경우다. 같다고 주장한 참조의 실제 주문 내용이 다르면 충돌이다.
의도는 명시적인 같은 version에서 BE 의도 내용 또는 GPT에 전달한 원문/출처가 달라지면 충돌이다.
버전이 없으면 같다고 추측하지 않는다. 지원하지 않는 프롬프트나 계약 버전은 기존 요청 검증에서
`INVALID_CURRENT_REQUEST`로 거절한다. 아직 실행할 수 없는 새 버전을 지원하는 것으로 가정하지 않는다.

모든 요청 필드를 비교하며, 프로필의 버전뿐 아니라 게시 상태·내용·관련 관측도 비교한다.
평가 ID나 trace만 달라도 원래 결과를 새 실행 결과로 바꾸지 않고 재평가를 요구한다.
결과 해시와 이전/현재 요청 해시를 보존하되 입력 원문이나 검증 예외 내용은 반환하지 않는다.
해시는 무결성 대조용이며 인증 서명이 아니다. 저장소·호출자의 신뢰성 확인은 외부 책임이다.

`MATCHED`는 내용 비교만 통과했다는 의미다. 실패/취소/부족 결과도 같은 요청에 연결될 수 있으며
재사용하려면 상태와 별도의 시각·만료·실제 모델 버전 검사까지 필요하다. 결제 승인 권한이 아니다.

## 직접 검증 — S15P21A207-292, 완료 기준 1·2·3·4의 연결 부분

가상환경 활성화 후 `ai/`에서 실행한다.

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/reuse/test_connection.py -v --tb=short
```

같은 입력, 변경된 주문/의도·이력·프로필·설정, 사용자/구매 혼동, 원본 훼손, 지원하지 않는 버전,
JSON 왕복과 무호출·불변성 항목이 모두 `PASSED`여야 한다. 자동 검증과 사용자 검증은 구별한다.
