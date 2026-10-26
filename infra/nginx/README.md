# Nginx

외부 요청을 받는 유일한 reverse proxy입니다. 내부 서비스 호출은 Nginx를 거치지 않고 Docker DNS를 사용합니다.

| 외부 경로 | 전달 대상 |
| --- | --- |
| `/mcp` | `mcp-server` |
| `/.well-known/oauth-protected-resource`, `/.well-known/oauth-protected-resource/mcp` | `mcp-server` |
| `/.well-known/oauth-authorization-server`, `/.well-known/jwks.json` | `core-service` |
| `/oauth/authorize`, `/oauth/authorize/**`, `/oauth/token` | `core-service` |
| `/api/core/**` | `core-service` |
| `/api/pay/**` | `pay-service` |
| `/api/pg/**` | `pg-mock` (개발 환경 전용) |
| `/.well-known/ucp` | `core-service` |
| `/merchant/v1/**` | `pay-service` |

`/internal`과 `/internal/**`는 명시적으로 차단합니다. 새 공개 경로를 추가할 때는 먼저 책임 서비스와 인증·인가 정책을 정하고, 내부 endpoint가 노출되지 않는지 확인합니다.

`/api/pg/`로 시작하는 요청은 경로와 query를 그대로 보존하여 `pg-mock:8080`으로 전달합니다. 예를 들어 `POST /api/pg/v1/payment-methods/tokenize`의 upstream 경로도 `/api/pg/v1/payment-methods/tokenize`입니다. `proxy_pass`에는 URI나 끝의 `/`를 붙이지 않습니다. 기존 `/v1/payment-methods/tokenize`는 외부에 노출하지 않으며, `/api/pgx/` 같은 유사 접두사도 전달하지 않습니다.

개발 환경의 토큰화 호출 예정 URL은 `https://j15a207.p.ssafy.io/api/pg/v1/payment-methods/tokenize`입니다. Nginx 라우팅 제공과 백엔드 API 준비는 별개이며, 실제 토큰화 연동은 담당 팀원의 엔드포인트 구현 및 개발 환경 반영 후 확인합니다. 이 경로는 개발용 테스트 카드에만 사용하며 운영 환경에서는 PG Mock을 외부에 공개하지 않습니다.

## 로컬 HTTP와 개발 서버 HTTPS

- 기본 `docker-compose.yml`은 `nginx.conf`를 사용합니다. 인증서 없이 `http://localhost:8080`에서 실행되며 기존 배포 스모크도 이 구성을 사용합니다.
- 개발 EC2는 `infra/docker/https.override.yml`을 함께 사용합니다. nginx 설정만 `nginx.https.conf`로 교체하고 443 포트, 인증서와 ACME 검증 경로를 추가합니다.
- 공개 주소는 `https://j15a207.p.ssafy.io/mcp`입니다. HTTP 요청은 경로와 query를 유지하여 HTTPS로 308 전환합니다. `/internal` 차단과 HTTP ACME 검증 경로는 리다이렉트 대상에서 제외합니다.
- HTTPS 설정은 Docker 내장 DNS를 재조회합니다. 백엔드 컨테이너가 교체되거나 잠시 중단되어도 nginx 설정 검사와 인증서 reload가 가능합니다. 이 설정은 Docker 기반 개발 EC2 전용입니다.
- HTTPS는 MCP 인증을 대체하지 않습니다. 무토큰 요청은 MCP의 `401 AUTHENTICATION_REQUIRED` 응답을 받습니다.

```bash
# 로컬: 기존 명령 유지
docker compose up --build

# 개발 EC2: 인증서 준비 후 설정 검사, nginx만 기동/재생성, reload
sh scripts/deploy-dev-nginx.sh

# 개발 EC2에서 Compose를 직접 실행할 때도 override를 포함합니다.
docker compose --env-file .env -f docker-compose.yml -f infra/docker/https.override.yml ps nginx
```

개발 EC2의 `.env`에는 `NGINX_PORT=80`을 지정합니다. HTTPS 포트 기본값은 443이며 `NGINX_HTTPS_PORT`로 바꿀 수 있습니다. 외부 기본 URL의 리다이렉트는 443을 사용하므로 공개 서비스에서는 기본값을 유지합니다. UFW 규칙은 배포 스크립트에서 변경하지 않습니다.

## 인증서와 자동 갱신

인증서 발급은 서버 최초 준비 작업입니다. CI는 발급·개인키 복사·자동 갱신 작업 설치를 수행하지 않으며 서버의 기존 인증서를 읽기 전용으로 연결합니다. 필요한 파일과 경로는 다음과 같습니다.

| 서버 경로 | 용도 |
| --- | --- |
| `/etc/letsencrypt/live/j15a207.p.ssafy.io/fullchain.pem` | 서버 인증서와 체인 |
| `/etc/letsencrypt/live/j15a207.p.ssafy.io/privkey.pem` | 개인키. Git·CI artifact 포함 금지 |
| `/etc/letsencrypt/archive/` | `live/` 심볼릭 링크가 참조하는 실제 인증서. 전체 `/etc/letsencrypt` 디렉터리를 마운트 |
| `/var/lib/letsencrypt/www/.well-known/acme-challenge/` | HTTP-01 검증 파일 |

현재 개발 서버는 `/usr/local/sbin/copenhagen-renew-tls`와 `copenhagen-certbot-renew.timer`로 하루 두 번 갱신 필요 여부를 확인합니다. 갱신 시 nginx를 검사한 뒤 reload하며, 실패한 reload 표식은 다음 실행까지 보존합니다. 저장소 배포는 이 서버 관리 파일과 인증서를 덮어쓰지 않습니다.

```bash
sudo systemctl status copenhagen-certbot-renew.timer
sudo /usr/local/sbin/copenhagen-renew-tls --dry-run --run-deploy-hooks
```

인증서가 없거나 잘못된 경우 `deploy-dev-nginx.sh`의 사전 검사가 실패하며 기존 nginx 컨테이너를 교체하지 않습니다. 설정 파일 자체의 이전 버전 복원은 자동화 범위에 포함되지 않으므로 실패 시 마지막 정상 Git 버전을 복원한 뒤 스크립트를 다시 실행합니다.

## 검증

`python3 scripts/test-nginx-config.py`는 별도 Docker 네트워크, 합성 백엔드, 임시 자체 서명 인증서를 사용합니다. 개발 서버 인증서·DB에는 접근하지 않으며 HTTP/HTTPS 경로, PG 접두사와 하위 경로, POST 본문과 query·헤더 전달, 리다이렉트, ACME, 기존 토큰화·내부 경로 차단, 백엔드 중단 중 TLS reload를 확인합니다. 실제 토큰화 API가 준비되지 않아도 실행할 수 있으며, 토큰화 기능 자체를 검증하지는 않습니다. Docker daemon, Compose v2, Python 3, OpenSSL이 필요합니다.
