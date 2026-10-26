# Task 506: 장바구니 변경 참조 진단

이 변경은 진단 로그만 추가한다. 참조의 15분 TTL, 메모리 저장, 소유자·연결·권한 검사와 외부 오류 코드는 유지한다.
아직 운영 재현으로 원인을 확정하지 않았다. 참조 생성 13초 뒤 조회 실패라는 기존 로그만으로는 동일 참조인지 알 수 없다.

## 수집

변경된 MCP 이미지 배포 후 EC2에서 아래 명령을 시작하고, ChatGPT에서 수량 변경을 한 번 실행한 뒤 Ctrl+C로 종료한다.

```bash
cd /opt/copenhagen
TRACE_LOG="$HOME/cart-reference-$(date -u +%Y%m%dT%H%M%SZ).log"
docker compose --env-file .env \
  -f docker-compose.yml -f infra/docker/https.override.yml \
  logs --since 1m --timestamps -f mcp-server 2>&1 | tee "$TRACE_LOG"
```

## 판독

`MCP action reference`의 `referenceHash`로 PREPARED와 조회를 연결한다.
`requestId`는 기존 `MCP timing`과 연결하며 `instance`는 JVM 시작마다 달라진다.
`userHash`, `connectionHash`는 각 요청의 인증 주체다. 생성 로그와 비교한다.
`ageMs=-1`은 생성 시각을 알 수 없다는 뜻이며 만료를 의미하지 않는다.
원문 참조, 토큰, 상품 인자, 계정 식별자는 기록하지 않는다. 해시는 익명화를 보장하지 않으므로 로그 접근은 제한한다.

| reason | 의미 |
| --- | --- |
| PREPARED / RESOLVED | 참조 생성 / 정상 조회 |
| INPUT_SCHEMA_REJECTED | 도구 실행 전 입력 스키마 거부 |
| ARGUMENT_SCHEMA_REJECTED / UNSUPPORTED_TOOL | 저장할 대상 도구 인자 또는 도구명 거부 |
| REFERENCE_NOT_FOUND | 해당 저장소에 참조 없음. 만료로 단정하지 않는다 |
| REFERENCE_EXPIRED | 저장된 참조의 TTL 경과 |
| EXPIRED_REMOVED | 다음 생성 요청에서 만료된 참조 정리 |
| OWNER_MISMATCH / CONNECTION_MISMATCH | 생성 때와 계정 / 연결이 다름 |
| INSUFFICIENT_SCOPE | 대상 도구 권한 부족 |
| CAPACITY_LIMIT | 저장 한도 도달 |
| SERIALIZATION_ERROR | 저장·조회 직렬화 실패 |

REFERENCE_NOT_FOUND이면 모든 MCP 인스턴스의 로그에서 동일 해시를 검색한다.
다른 instance의 PREPARED가 있으면 재시작·분산 메모리 경로를 조사한다.
EXPIRED_REMOVED가 있으면 만료 정리 경로다. PREPARED가 없으면 로그 누락·수집 기간·다른 참조 전달을 구분해야 한다.
입력 스키마 거부는 생성되지 않은 참조일 수도 있으므로 임의 재시도하거나 새 변경 요청을 실행하지 않는다.
인증/진단 세션 래퍼에서 먼저 거부되면 기존 `MCP auth rejected`와 requestId를 함께 확인한다.

## 완료 기준

운영 재현의 PREPARED, 조회 실패, MCP timing을 함께 대조하고 확인된 분기와 근거를 Task 506에 기록한다.
현재 단계는 로컬 계측 구현이며 운영 원인 확정, 복구 UX 및 저장 방식 변경은 포함하지 않는다.
