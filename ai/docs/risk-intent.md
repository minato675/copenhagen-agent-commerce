# GPT 의미·실행 상태 정책 신호

`interpret_risk_intent(supplied)`는 이미 계산된 `RiskPolicyInput.intent`를 읽고 정책 신호를 반환한다. 입력의 주문·구매 의도·추적 ID·평가 시각을 다시 검증한다. GMS 호출, 재시도, Feature 점수 계산은 수행하지 않는다.

```python
from copenhagen_fds.risk_intent import interpret_risk_intent
from copenhagen_fds.contracts.risk_intent import RiskIntentSignal

# supplied: 기존 행동 평가와 IntentComparisonResult를 담은 RiskPolicyInput
result = interpret_risk_intent(supplied)
print(result.signal, [reason.code for reason in result.reasons])
restored = RiskIntentSignal.model_validate_json(result.model_dump_json())
restored.validate_for_input(supplied)
```

| GPT 실행 상태 | 의미 결과 | 정책 신호 | 정책 사유 |
| --- | --- | --- | --- |
| SUCCESS | MATCH | MATCH | 없음 |
| SUCCESS | MISMATCH | MISMATCH | INTENT_MISMATCH |
| SUCCESS | UNKNOWN | UNKNOWN | INTENT_UNKNOWN |
| FAILED | 없음 | FAILED | INTENT_FAILED |
| NOT_RUN | 없음 | NOT_RUN | INTENT_NOT_RUN |

`source`에 원본 결과 전체를 보존한다. 불일치의 비교 차원·상품 참조·확인 요청, 모델·프롬프트 버전, 호출 측정값, 실패 종류·재시도 가능 여부·진단 정보가 유지된다. 요약 사유는 원본 상세 근거를 대체하지 않는다. MATCH에 사유가 없다는 것은 의미 불일치가 없다는 뜻이며 거래가 안전하다는 판정이 아니다. GPT 의미를 숫자 점수나 사기 확률로 변환하지 않는다.

만료는 여섯 번째 의미 결과가 아니다. 평가 시각보다 미래이거나, 나이가 정책의 `intent_max_age_seconds` 이상이거나, `valid_until <= evaluated_at`이면 입력 검증 오류로 거절한다. 다른 사용자·구매 요청·주문 버전·구매 의도 버전·추적 ID 및 현재 주문에 없는 상품 참조도 거절한다. 호출자가 올바른 결과를 제공한 뒤 다시 평가해야 한다.

JSON 복원은 신호와 원본 해시의 일관성을 검사한다. 실제 입력과의 대상·정책·시각 연결까지 검사하려면 `validate_for_input(supplied)`를 호출한다. 해시는 내용 연결용이며 발급자 인증 수단이 아니다.

## 직접 검증

저장소의 `ai` 디렉터리에서 실행한다. 아래 명령은 PowerShell과 Git Bash에서 사용할 수 있다. `python 파일.py`로 실행하지 않고 `-m pytest`를 사용한다.

```sh
./.venv/Scripts/python.exe -m pytest tests/learning/personalization/evaluation/risk/test_intent.py -v --tb=short
```

예상 결과는 **30 passed**이다. 각 `PASSED` 옆 함수 이름이 검증한 조건이다. `rejected` 테스트는 잘못된 입력에 검증 오류가 발생해야 통과한다. 실제 GMS 키나 네트워크 연결은 필요하지 않다.

의미/실행 상태 결정표는 `-k signal_decision_table`, 만료 경계는 `-k 'freshness or expiry'`, 불일치 상세 보존은 `-k detailed_findings`, 잘못된 연결은 `-k 'target_binding or foreign_item or source_and_policy'`를 위 명령 끝에 붙여 따로 확인할 수 있다.
