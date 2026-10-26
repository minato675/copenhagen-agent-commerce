# 통합 FDS 실행 CLI

가상환경을 활성화한 뒤 `ai/`에서 실행한다.
준비된 입력이 없다면 [통합 재현 시나리오](native-scenarios.md)의 `prepare-demo`부터 시작한다.

```bash
python -m copenhagen_fds.native_cli --help
python -m copenhagen_fds.native_cli evaluate input.json --mock-script mock-script.json --output results/evaluation/native-first
```

`input.json`은 `EngineEvaluationRequest` JSON이다. 기존 통합 계약의 요청 전체를 사용한다.
`mock-script.json`은 `clock_start`(시간대가 있는 명시적 시각), `extraction`, `comparison`을 가진다.
두 응답은 각각 `IntentExtractionOutput`/`IntentComparisonOutput` 또는 `AnalysisFailure` JSON이다.
Mock은 제공자 응답만 대체한다. 의도 추출과 주문 비교의 프롬프트 생성·파싱 결과 검증·원본 연결,
행동 점수 계산과 최종 위험 정책은 `evaluate_fraud`를 통해 실제 실행한다.

기본 모드는 Mock이며 환경의 `FDS_MODE=LIVE`로 바뀌지 않는다. 키와 env 파일을 읽지 않고
소켓/DNS를 차단한다. 원본 요청의 gateway는 MOCK이어야 하며 시계는 행동 기준보다 빨라서는 안 된다.
전용 순차 CLI 프로세스 안에서만 고정 시계와 네트워크 차단을 사용한다. 동시 서버 요청용 API가 아니다.
Mock 예산의 경과 시간은 0으로 고정한 합성 값이며 성능 측정값이 아니다. 실제 지연 시간·토큰 수를
지어내지 않고 `wall_duration_ms=null`, `external_api=NOT_RUN`으로 표시한다.

실제 GMS를 사용하려면 다음과 같이 명시적으로 선택한다.

```bash
python -m copenhagen_fds.native_cli evaluate input.json --mode gms --env-file .env --output results/evaluation/native-gms
```

이 명령은 실제 API 비용이 발생할 수 있다. 기존 GMS Secret·모델·제한 시간 설정을 사용한다.
원본 요청을 보관하고 실행용 복사본의 gateway·모델·제한 시간만 실제 설정으로 바꿔 함께 기록한다.
행동 기준 시각·이력·정책은 바꾸지 않는다. 과거 합성 입력으로 호출하면 과거 행동 스냅샷과
현재 GPT 응답을 결합하는 기능 확인이며 실제 현재 거래의 품질 검증이 아니다.
GMS 모드에는 Mock 응답/고정 시계를 전달할 수 없다. 실제 완료 시각·경과 시간·토큰 수가 반환된
범위만 기록하며 성공/실패/미실행을 구분한다. 실패 시 Mock으로 대체하거나 재시도하지 않는다.

새 출력 폴더만 사용한다. 기존 출력·입력과 겹치면 호출 전에 거절하며 덮어쓰거나 삭제하지 않는다.
실행 중 오류로 빈/일부 출력이 남아도 기존 폴더를 재사용하지 말고 새 경로를 사용한다.

| 산출물 | 내용 |
| --- | --- |
| `source-request.json`, `request.json` | 원본 요청 및 실제 실행 요청 |
| `mock-script.json` | Mock에서 사용한 고정 시계와 제공자 응답 |
| `result.json` | 단계별 원본과 최종 `NativeEvaluationResult` |
| `summary.json` | 점수·단계·상태·위험·이유·권고·호출 단계·불변성 |
| `manifest.json` | 산출물 해시, 코드·Python/의존성 버전, 실행 모드 |

결과 파일에는 요청·이력·프로필이 포함되므로 내부 검증 자료로 관리한다. Secret 설정과 예외 원문은
출력하지 않는다. 해시는 무결성 대조용이며 서명이 아니다. `completion_invocations`는 제공자 어댑터
호출 단계 목록으로, 과금된 HTTP 요청 수를 입증하는 값이 아니다. CLI는 DB·실제 주문 상태를 조회하거나
평가 중 학습·프로필 갱신·결제를 실행하지 않는다. 종료 코드는 정상 완료 0, 잘못된 입력/실행 설정 2,
평가 실패/자료 부족 3이다. 이는 사기 확률이나 실제 결제 승인 코드가 아니다.

## 직접 검증 — S15P21A207-293, 완료 기준 2·3의 실행/격리 부분

```bash
python -m pytest tests/learning/personalization/replay/engine/analysis/finalization/cli/test_cli.py -v --tb=short
```

기본 키 없는 실행·명시적 실제 모드·단계 실패/미실행·출력 보호·예외 비노출·시계 복원 검증이 모두
PASSED여야 한다. 실제 네트워크 호출 성공은 별도 검증이며 이 테스트 통과만으로 주장하지 않는다.
