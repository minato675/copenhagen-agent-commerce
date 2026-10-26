# 장바구니 일괄 추가 성능 측정 — Story 526 / Task 527·530

## 기준선

- 기준 커밋: develop efc4e42 (2026-09-28).
- 신규 추가는 `add_cart_item` N회. 검색과 옵션은 최대 8개 일괄 도구 사용 가능.
- 기존 `apply_cart_changes`는 수정·삭제 전용이며 신규 추가를 지원하지 않는다.
- 현재 운영 표본은 확보하지 않았다. 화면의 769ms/80ms/15~21ms는 검증된 기준선이 아니다.

## 동일 조건 비교

1. 테스트 계정에서 상품·옵션·수량이 확정된 1/4/8개 목록을 준비한다. 결제하지 않는다.
2. 모델·호스트·이미지 SHA·상품 목록·초기 장바구니 상태·동시 사용자 수를 기록한다.
3. 단건/일괄 각각 예비 3회, 본 측정 최소 20회. 실행 사이 테스트 장바구니를 같은 상태로 복구한다.
4. 요청 전송→최종 카드 표시 전체 시간을 화면에서 측정한다. 질문·사용자 승인 시간은 별도 기록한다.
5. docs/api/mcp-latency-qa-499.md 명령으로 실행별 MCP timing 로그를 수집한다.
6. `python scripts/analyze-mcp-timing.py RUN.log --observed-seconds ACTUAL_SECONDS`로 분석한다.
7. 호출 수, 전체 p50/p95, MCP 실행 구간 합집합, Core HTTP 수·왕복 시간을 비교한다.

Core HTTP 시간은 내부 서버와 판매처·네트워크를 포함한다. 모델 추론 시간으로 역산하지 않는다.
병렬 실행의 시간 합계를 전체 대기시간에서 빼지 않는다. 서버 내부 세부 계측이 없으면 분리 불가로 기록한다.
토큰·본문·사용자 식별정보를 수집하지 않는다. 운영 캐시를 지우거나 서비스를 재시작하지 않는다.

## 검색 변경 영향

9c9ec2ef와 efc4e42를 같은 환경에서 비교한다. 식재료/부속품이 혼동되는 동일 질의와
단일·동시 검색 부하를 사용한다. 응답 시간뿐 아니라 상위 결과의 적합성도 기록한다.
검색 품질 개선에 따른 재검색 감소와 개별 검색 CPU 비용을 분리한다.

## 판단 기준

MCP 일괄 추가는 모델과 도구 사이 왕복을 N→1로 줄인다. Core 쓰기는 N회 유지된다.
실측에서 내부 통신·장바구니 스냅샷 생성 비용이 지배적일 때 Core 일괄 API를 후속 검토한다.
전체 시간 개선 수치와 Core 일괄 API 필요성은 운영 표본 확보 전 확정하지 않는다.

| 항목 | 상태 |
| --- | --- |
| 코드 기준선·측정 절차 | 확인 |
| GPT/Claude 전체 대기시간 | 미측정 |
| EC2 단건/일괄 p50·p95 | 미측정 |
| 검색 순위 변경 전후 CPU·지연 | 미측정 |

## 비교 도구 — Task 530

각 실행의 분석기 출력 JSON을 저장한 뒤 다음 manifest 형식으로 묶는다.
아래 시간은 형식 설명용 예시이며 실측치로 교체해야 한다.

```json
[
  {"mode":"single","item_count":8,"observed_seconds":60,"environment":"host-model-serverSHA-warm","report_file":"single-01.json"},
  {"mode":"batch","item_count":8,"observed_seconds":40,"environment":"host-model-serverSHA-warm","report_file":"batch-01.json"}
]
```

```bash
python scripts/compare-cart-timing.py runs.json
```

환경·상품 수·방식별 전체 p50/p95 및 도구/Core 호출 수를 출력한다.
실패·불완전 로그를 성공 성능에 섞지 않으며 20회 미만은 참고값으로 표시한다.
환경 문자열에 계정·개인정보를 넣지 않는다.

## 로컬 검증 결과와 한계

- 1/4/8개 상품을 MCP 핸들러 한 번으로 처리하고 Core POST는 각각 1/4/8회인 구조를 테스트한다.
- 응답 유실 후 전체 재시도, 재시작, 중간 실패 중단, 권한/입력 검증을 테스트한다.
- 이 테스트는 Core 대역을 쓰므로 EC2·GPT 성능 측정값이 아니다.
- Core 일괄 API는 아직 도입하지 않았다. 내부 왕복·DB 비용의 운영 실측 자료가 필요하다.
- Task 527·530의 실제 기준선/변경 후 p50·p95와 검색 변경 영향 측정은 배포 후 별도 수집이 남아 있다.

검증 실행(2026-09-29): MCP 테스트 283개, Core 장바구니·멱등 테스트 18개 통과.
MCP main/test Checkstyle 통과. Python timing 분석·비교 테스트 8개 통과.
실행된 명령은 다음과 같다.

```bash
./backend/gradlew -p backend :apps:mcp-server:test :apps:mcp-server:checkstyleMain :apps:mcp-server:checkstyleTest
./backend/gradlew -p backend :apps:core-service:test --tests '*InternalCartServiceTest' --tests '*IdempotencyStoreTest'
python -m unittest discover -s scripts/tests -p '*timing.py'
```

Gradle wrapper는 backend/gradlew 위치에 있다(Windows에서는 backend/gradlew.bat).
