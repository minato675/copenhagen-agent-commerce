# Docker와 개발 EC2 구성

레포 루트의 `docker-compose.yml` 하나로 로컬 환경과 개발 EC2를 구성합니다. 애플리케이션 5개, PostgreSQL 3개, Redis, Nginx가 각각 별도 컨테이너로 실행됩니다.

개발 EC2의 애플리케이션 image는 Registry에서 pull하지 않습니다. GitLab Runner가 `docker save | gzip | ssh`로 직접 스트리밍하고 EC2에서 `gunzip | docker load`합니다. PostgreSQL, Redis, Nginx 같은 공개 기반 image만 Docker Hub에서 pull합니다.

## 로컬에서 실행하기

Docker Engine과 Docker Compose v2가 필요합니다. 레포 루트에서 실행합니다.

```bash
docker compose up --build
docker compose ps
```

처음 실행할 때 image를 만들고 DB volume을 생성하므로 시간이 걸릴 수 있습니다. 각 애플리케이션이 `healthy`인지 확인합니다.

```bash
docker compose logs -f core-service
docker compose down
```

`docker compose down`은 DB 데이터를 보존합니다. `docker compose down --volumes`는 PostgreSQL과 Redis 데이터를 삭제하므로 초기화가 필요할 때만 실행합니다.

## Compose 구성

| Compose service | image 환경변수 | 역할 |
| --- | --- | --- |
| `nginx` | 해당 없음 | 외부 ingress |
| `core-service` | `CORE_IMAGE` | UCP와 구매 흐름 조정 |
| `mcp-server` | `MCP_IMAGE` | Agent-facing adapter |
| `pay-service` | `PAY_IMAGE` | 결제 처리 |
| `ucp-merchant-mock` | `MERCHANT_MOCK_IMAGE` | 외부 Merchant 시뮬레이터 |
| `pg-mock` | `PG_MOCK_IMAGE` | 외부 PG 시뮬레이터 |
| `core-db` | 해당 없음 | Core 전용 PostgreSQL |
| `pay-db` | 해당 없음 | Pay 전용 PostgreSQL |
| `merchant-db` | 해당 없음 | Merchant Mock 전용 PostgreSQL |
| `redis` | 해당 없음 | 아직 용도가 정해지지 않은 공유 인프라 |

로컬에서는 image 환경변수가 없으면 `copenhagen/<service>:local`을 빌드합니다. 개발 EC2에서는 CI가 load한 `copenhagen/<service>:<commit-sha>`를 `.env`에 기록합니다.

## 내부 통신 원칙

컨테이너끼리는 Compose service name을 DNS 이름으로 사용합니다.

```text
mcp-server        → http://core-service:8080
core-service       → http://pay-service:8080
core-service       → http://ucp-merchant-mock:8080
ucp-merchant-mock → http://pay-service:8080
pay-service        → http://pg-mock:8080
```

내부 통신에 EC2 public IP, `localhost`, Nginx 주소를 사용하지 않습니다. Nginx는 외부 요청만 받습니다.

## 사용자가 EC2에서 최초 한 번 할 일

첫 `develop` 배포 pipeline을 실행하기 전에 완료해야 합니다.

### 1. Docker 준비

EC2에 Docker Engine과 Docker Compose v2 plugin을 설치합니다. 설치 방법은 사용하는 AMI에 맞는 Docker 공식 절차를 따릅니다.

다음 두 명령이 모두 성공해야 합니다.

```bash
docker version
docker compose version
```

배포 SSH 사용자가 `sudo` 없이 Docker를 실행할 수 있는지도 확인합니다.

```bash
docker ps
```

### 2. SSH와 네트워크 준비

- GitLab CI가 사용할 SSH private key에 대응하는 public key를 EC2 배포 사용자의 `authorized_keys`에 등록합니다.
- EC2 Security Group은 GitLab Runner가 SSH port에 접근할 수 있게 설정합니다.
- 불필요하게 SSH를 전 세계에 공개하지 말고 가능한 범위로 제한합니다.
- 배포 사용자가 `DEV_DEPLOY_PATH`에 파일을 만들고 수정할 수 있어야 합니다.

### 3. 배포 디렉터리 준비

첫 merge 전에는 현재 기능 branch를 EC2에 clone하거나 필요한 파일을 안전하게 복사하여 초기 구성을 준비합니다. 배포 디렉터리는 GitLab 변수 `DEV_DEPLOY_PATH`와 정확히 같아야 하며 공백이 없는 절대 경로를 권장합니다.

필요한 초기 파일은 다음과 같습니다.

```text
docker-compose.yml
infra/docker/ec2.env.example
infra/docker/https.override.yml
infra/nginx/nginx.conf
infra/nginx/nginx.https.conf
scripts/bootstrap-dev-infra.sh
scripts/deploy-dev-service.sh
scripts/deploy-dev-nginx.sh
```

### 4. EC2 `.env` 만들기

배포 디렉터리에서 실행합니다.

```bash
cp infra/docker/ec2.env.example .env
chmod 600 .env
```

`.env`의 `CORE_DB_PASSWORD`, `PAY_DB_PASSWORD`, `MERCHANT_DB_PASSWORD`에 있는 모든 `CHANGE_ME`를 개발 서버용 비밀번호로 바꿉니다.

- 실제 `.env`를 Git에 commit하지 않습니다.
- production 비밀번호를 개발 서버에 사용하지 않습니다.
- `CORE_IMAGE` 같은 애플리케이션 image 값은 직접 작성하지 않아도 됩니다. 첫 배포 때 CI가 기록합니다.

### 5. DB와 Redis 시작

```bash
./scripts/bootstrap-dev-infra.sh
```

이 스크립트는 `.env`와 비밀번호를 검사하고 다음 공유 인프라만 시작합니다.

```text
core-db
pay-db
merchant-db
redis
```

확인합니다.

```bash
docker compose --env-file .env ps
```

DB 3개와 Redis가 `healthy`이면 [nginx HTTPS 준비](../nginx/README.md#인증서와-자동-갱신)에 따라 서버 인증서와 자동 갱신을 준비합니다. 개발 EC2의 `.env`에는 `NGINX_PORT=80`을 지정합니다. CI는 인증서가 준비된 뒤 첫 `develop` 배포를 실행하며, 개인키를 전달하거나 방화벽을 변경하지 않습니다.

## 첫 자동 배포 후 할 일

GitLab에서 서비스별 `deploy-dev` job이 성공했는지 확인한 뒤 EC2에서 실행합니다.

```bash
cd <DEV_DEPLOY_PATH>
docker compose --env-file .env ps
docker image ls 'copenhagen/*'
```

CI는 HTTPS 사전 검사를 통과한 nginx를 먼저 적용한 후 각 서비스를 배포합니다. 수동으로 nginx를 다시 적용할 때도 HTTPS 전용 스크립트를 사용합니다.

```bash
sh scripts/deploy-dev-nginx.sh
```

이후 서비스 배포는 GitLab CI가 자동으로 수행합니다.

## 자동 배포가 하는 일

Core만 변경된 예시는 다음과 같습니다.

```text
GitLab CI에서 core-service image 생성
→ SSH 압축 스트림을 EC2 Docker에 직접 load
→ HTTPS nginx 설정 사전 검사와 적용
→ .env의 CORE_IMAGE만 새 commit SHA로 변경
→ core-service 컨테이너만 재생성
→ health check
```

`--no-deps --no-build`를 사용하므로 Core 배포 때문에 Pay, DB 등의 컨테이너를 재시작하지 않습니다. 실패하면 `.env`는 이전 image를 유지하고, 그 image가 EC2에 남아 있으면 복구를 시도합니다.

## 운영 중 확인 명령

```bash
cd <DEV_DEPLOY_PATH>
docker compose --env-file .env ps
docker compose --env-file .env logs --tail=200 <service-name>
docker image ls 'copenhagen/*'
docker system df
```

commit SHA image가 계속 쌓이면 EC2 disk 사용량도 증가합니다. 자동으로 삭제하면 rollback image까지 지울 수 있으므로 현재 pipeline은 image prune을 수행하지 않습니다. 팀이 보관 기간을 정한 뒤 사용하지 않는 오래된 image를 정리합니다.

## 문제 해결

- `Cannot connect to the Docker daemon`: Docker 실행 상태와 배포 사용자의 Docker 권한을 확인합니다.
- image tag가 없음: 해당 서비스의 `docker-build` job에서 SSH 적재가 성공했는지 확인합니다.
- `docker image load` 실패: EC2 disk 여유 공간과 SSH 스트림 전송 로그를 확인합니다.
- `unhealthy`: 해당 서비스 로그, DB 상태, `.env` 연결 값을 확인합니다.
- SSH 또는 SCP 실패: GitLab CI 변수, Security Group, `known_hosts`, 배포 경로 권한을 확인합니다.
- `port is already allocated`: `.env`의 `NGINX_PORT`와 EC2에서 이미 사용하는 port를 확인합니다.

GitLab 변수 등록 방법은 [GitLab CI/CD와 EC2 직접 배포](../gitlab/README.md)를 참고합니다.
