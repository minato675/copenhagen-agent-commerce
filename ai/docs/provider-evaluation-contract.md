# BE FDS 평가 데이터 계약

관련 Task: S15P21A207-299. 이 문서는 BE가 전달할 스냅샷과 AI가 반환할 결과의 데이터 계약이다. HTTP 주소·서비스 인증·실행 서버는 아직 확정하지 않았다.

## 요청

`copenhagen_fds.contracts.provider_evaluation.ProviderEvaluationRequest`를 사용한다.

| 필드 | 의미 |
| --- | --- |
| `schema_version` | JSON에는 `fds-provider-v1`을 명시한다. |
| `operation` | `EVALUATE` |
| `call.request_id` | 호출 식별자. 값 자체가 멱등성 처리나 중복 실행 방지를 보장하지 않는다. |
| `call.trace_id` | 내부 평가의 trace와 동일한 추적 식별자 |
| `call.sent_at` | 시간대가 포함된 전송 시각. 행동 정보 기준 시각 이상이어야 한다. |
| `configuration` | 설정 식별자·버전·내용 해시. 공급자가 신뢰하는 설정과 대조할 참조이다. |
| `evaluation` | 기존 `EngineEvaluationRequest` 전체 입력. 원문·주문·히스토리·전달된 프로필·설정 스냅샷을 포함한다. |

내부 엔진의 사용자·구매 요청·주문 버전/해시·원문 출처/버전·프로필 소유자·통화·공개 시각 연결 검증을 재사용한다. 제공자 호출에서는 위험 정책의 9개 피처를 모두 요청해야 한다. 수집되지 않은 피처는 기존 `NOT_COLLECTED`/`INSUFFICIENT_HISTORY` 입력 의미로 나타낸다. 피처 목록을 줄여 자료 부족을 숨기지 않는다.

`evaluation_configuration_hash(evaluation)`는 초기 정책·개인화 설정·개인 점수 정책·위험 정책·실행 설정을 함께 묶는다. 이는 요청 내부 일관성 검사다. 호출자가 본문과 해시를 함께 바꿀 수 있으므로 인증이나 설정 승인으로 사용하면 안 된다. 후속 실행 경계는 **독립적으로 신뢰하는 설정**의 식별자·버전·내용을 비교한 뒤 실행해야 한다. 설정 저장 위치와 승인 주체는 BE와 미정이다. GMS 키·인증 토큰을 받는 본문 필드는 없다.

`sent_at`은 클라이언트 주장이다. 제한 시간 측정에는 공급자가 요청을 받은 시점부터 시작한 별도 단조 시계를 사용해야 한다. 임의의 전송 시각으로 요청 예산을 연장하면 안 된다.

평가 호출은 프로필을 조회·저장하거나 생성·갱신하지 않는다. BE가 평가 전에 필요한 자료를 준비한다. 현재 구매 요청이 포함된 프로필 이력은 거절한다.

## 응답

`ProviderEvaluationResponse`는 전체 `NativeEvaluationResult`를 BE의 일반 평가 응답으로 그대로 내보내지 않는다.

| 필드 | 의미 |
| --- | --- |
| `call`, `request_sha256`, `engine_reference` | 원래 호출 및 전체 입력의 연결 근거. 해시는 인증 서명이 아니다. |
| `record_sha256` | 별도 내부 실행 기록의 내용 해시. 내부 기록의 저장·전달 경로는 별도 합의 대상이다. |
| `completed_at` | 공급자 계산 완료 시각 |
| `outcome` | `SUCCESS`/`INCOMPLETE`/`FAILED`/`CANCELLED`, 위험 수준, 확인 권고와 후속 조치 |
| `behavior_score` | 정책이 집계한 0~100 행동 이탈 점수. 사기 확률이 아니다. |
| `score_coverage`, `groups` | 최종 정책 집계의 점수 가용성 및 금액·빈도·시간 그룹/피처 기여도 |
| `features` | 피처별 상태·점수, `COLD`/`TRANSITION`/`PERSONALIZED`/`UNAVAILABLE`, 혼합 가중치, 최근/장기 충분도 요약 |
| `reasons` | 정형 위험/자료 부족/실패 사유 |
| `stages` | 행동·원문 추출·의도 비교·위험 정책의 실행 상태와 시각, 실패/미실행 이유 |
| `versions` | 피처/점수/개인화/위험 정책, 요청 모델, 실제 응답 모델, 프롬프트, 개인·집단 프로필 버전 |
| `result_valid_until` | 별도로 부여된 배타적 만료 시각. 미부여는 `null`이며 기본 TTL을 만들지 않는다. |
| `reuse_status` | 이 응답 자체에서는 `NOT_ASSESSED`. 만료 시각 존재만으로 재사용을 허용하지 않는다. |
| `storage_status` | 항상 `NOT_PERSISTED`. AI 계산 성공과 BE 저장 성공은 별개다. |

입력 원문, 주문 상품 상세, retained samples, GPT 응답 payload, 프롬프트와 호출 telemetry는 이 응답에 들어가지 않는다. 참조에 필요한 사용자/구매/프로필 ID는 남으므로 일반 공개용 응답이나 익명 데이터는 아니다.

자료 부족은 `INCOMPLETE`, 위험 `UNKNOWN`, 권고 `null`로 보존한다. 누락 점수는 `null`이며 0점으로 채우거나 나머지 피처로 재정규화하지 않는다. 실패·취소 결과를 정상 거래로 해석하지 않는다. 위험 정책 실행이 실패하면 `groups`/최종 점수는 없을 수 있지만 이미 계산된 `features`의 점수·단계는 보존할 수 있다. 따라서 `score_coverage`는 최종 정책 집계의 가용성이고, 개별 계산 여부는 `features[].status`에서 확인한다.

응답을 디코딩한 뒤 `response.validate_for_request(request)`를 호출해 전체 요청 해시·대상·정책 버전·프로필 참조·그룹 가중치를 연결한다. 이 검사는 저장된 내부 기록과 요약이 실제로 같은 계산에서 나왔음을 암호학적으로 증명하지 않는다. 내부 결과로부터의 변환은 별도 제공자 평가 연결 구현의 책임이다.

AI는 결제 승인/차단/실행을 집행하지 않는다. `NO_ADDITIONAL_REVIEW`도 BE 인증과 승인 절차를 대체하지 않는다. 저장된 결과 재사용은 기존 `check_engine_result_reuse`의 요청 연결·정책/모델/프로필 변경·유효성 검사를 별도로 수행해야 한다.

## 직접 검증

저장소의 `ai/`에서 가상환경 Python으로 실행한다. 관련 Task: S15P21A207-299.

```bash
python -m pytest tests/provider/test_evaluation.py -v --tb=short
```

정상·개인 프로필·자료 부족·GPT 실패·미실행의 JSON 왕복과 요청 연결, 다른 사용자/버전/시각 거부, 점수 범위·자료 비노출 검사가 모두 `PASSED`여야 한다. 이 테스트는 기존 실제 FDS 계산과 합성 GPT 응답을 사용하며 실제 GMS를 호출하지 않는다.
