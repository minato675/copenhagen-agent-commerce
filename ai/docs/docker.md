# AI Docker 실행 환경

관련 Task: S15P21A207-394

기존 FDS Python 패키지와 FastAPI 단독 서버를 실행하는 CPU용 Linux 이미지다. 기본 실행은 8000 포트의 Uvicorn 서버이며 CLI 명령을 지정해 실행할 수도 있다. 실제 Core/Pay 연결과 운영 DB는 후속 작업이다. 실제 GMS 호출이나 결제 처리는 자동으로 실행되지 않는다.

## 빌드

Docker Desktop의 Linux 엔진 또는 Linux Docker Engine을 실행한다. 아래 명령은 **저장소 루트**에서 PowerShell과 Bash 모두 사용할 수 있다.

```sh
docker build -f ai/Dockerfile -t copenhagen/ai:local ai
```

마지막 `ai`가 빌드 컨텍스트다. `.dockerignore`는 기본적으로 모든 파일을 제외하고 패키지 소스, 빌드 메타데이터, 공개 예제만 허용한다. 루트 `.env`, `ai/.env`, `.venv`, 테스트, 개인 데이터, 모델 산출물, 평가 결과는 전송하지 않는다. 패키지는 editable 설치 없이 빌드 단계에서 설치하고 설치 결과만 실행 이미지로 복사한다. 패키지 다운로드를 위해 빌드 시 네트워크가 필요하다.

Python 3.11.16 기반 이미지는 digest로 고정한다. 직접 의존성은 `pyproject.toml`의 버전을 사용하며, 전이 의존성은 기존 프로젝트와 같이 pip가 결정한다. 따라서 이후 재빌드의 모든 의존성이 같다는 보장은 없다. 재현 검증에는 같은 빌드 이미지를 사용하고, 배포 파이프라인 연결 시 커밋별 이미지 태그와 이미지 digest를 기록한다.

## 직접 검증

다음 항목은 모두 Task S15P21A207-394와 연결된다. 검증 명령은 GMS 키와 네트워크 없이 실행한다.

```sh
# CLI 실행: 도움말을 출력하고 종료 코드 0으로 종료
docker run --rm --network none copenhagen/ai:local python -m copenhagen_fds.provider_cli --help

# 의존성: No broken requirements found.
docker run --rm --network none copenhagen/ai:local python -m pip check

# 비관리자 실행: uid=10001(fds), gid=10001(fds)
docker run --rm --network none copenhagen/ai:local id

# 기존 합성 FDS 점수 계산: 피처별 상태와 점수 JSON 출력
docker run --rm --network none copenhagen/ai:local python examples/cold_scoring_demo.py

# 이력 부족: 빈 이력을 정상 0건으로 간주하지 않는 결과 확인
docker run --rm --network none copenhagen/ai:local python examples/cold_scoring_demo.py --scenario missing_history
```

기존 CLI 입력 및 결과의 의미는 [Cold 점수 문서](cold-scoring.md)와 [제공자 Mock 실행 문서](provider-handoff.md)를 참고한다. 컨테이너 실행 검증은 탐지 정확도나 실제 BE 통합 성공을 의미하지 않는다.

## 평가 결과 보존

출력 파일은 `/app/results`에 쓰고 Docker 볼륨이나 호스트 디렉터리를 연결해 보존한다. 컨테이너 내부에만 쓴 파일은 `--rm`으로 컨테이너를 삭제하면 사라진다. 아래 예시는 이름이 있는 볼륨을 사용한다. 각 출력 디렉터리는 기존 CLI 규칙에 따라 아직 존재하지 않아야 한다.

```sh
docker volume create copenhagen-ai-results
docker run --rm --network none --mount type=volume,source=copenhagen-ai-results,target=/app/results copenhagen/ai:local python -m copenhagen_fds.provider_cli prepare-demo --output results/provider-input
docker run --rm --network none --mount type=volume,source=copenhagen-ai-results,target=/app/results copenhagen/ai:local python -m copenhagen_fds.provider_cli suite results/provider-input --output results/provider-run
docker run --rm --network none --mount type=volume,source=copenhagen-ai-results,target=/app/results copenhagen/ai:local python -m copenhagen_fds.provider_cli show results/provider-run
```

`prepare-demo`는 `PREPARED`, `suite`는 `PASSED`가 기대 결과다. 다시 실행할 때는 새 출력 디렉터리 이름을 사용한다. 입력 생성과 평가에는 같은 이미지를 사용한다. CLI는 Python·패키지 버전과 코드 지문을 검사하므로 로컬 venv 또는 다른 이미지에서 만든 결과를 그대로 재현 검증할 수 있다고 가정하지 않는다.

Linux에서 호스트 디렉터리를 연결한다면 컨테이너 사용자 UID/GID `10001:10001`이 쓸 수 있는 경로를 준비한다. 이 볼륨 예제는 CLI 산출물 보존용이며 사용자 프로필·결제 이력용 운영 저장소는 별도로 구현해야 한다.

## FastAPI 서버 실행

설정 없이 시작하면 `/health/live`는 200, `/health/ready`와 평가 API는 503이다. 이미지의 healthcheck는 프로세스 응답만 확인하며 외부 GMS 호출이나 운영 준비 상태를 보증하지 않는다.

```sh
docker run --rm -d --name copenhagen-ai-local -p 127.0.0.1:8000:8000 copenhagen/ai:local
docker logs copenhagen-ai-local
docker stop copenhagen-ai-local
```

[HTTP API 사용법](http-api.md)에서 Mock 설정을 생성한 뒤, **저장소 루트의 PowerShell**에서 다음과 같이 서버를 실행할 수 있다.

```powershell
$apiConfigDir = (Resolve-Path 'ai/results/evaluation/http-demo-394').Path
$env:AI_HTTP_SERVICE_TOKEN = [Guid]::NewGuid().ToString('N')
docker run --rm -d --name copenhagen-ai-local -p 127.0.0.1:8000:8000 --mount "type=bind,source=$apiConfigDir,target=/run/fds,readonly" --env AI_HTTP_CONFIG=/run/fds/server.json --env AI_HTTP_SERVICE_TOKEN copenhagen/ai:local
```

서버 시작 후 같은 창에서 `Invoke-RestMethod http://127.0.0.1:8000/health/ready`를 실행하면 READY/MOCK을 반환해야 한다. 평가 요청은 HTTP API 문서의 예제에서 `-InFile` 경로 앞에 `ai/`를 붙여 사용한다. 검증 후 `docker stop copenhagen-ai-local`로 종료하고 `Remove-Item Env:AI_HTTP_SERVICE_TOKEN`으로 토큰을 제거한다. 기본 서버는 worker 1개이며 access log와 proxy header 신뢰를 끈다. 루트 Compose 및 CI/CD 등록은 인프라 담당자와 연결한다.

GMS 키는 실행 시 필요한 환경변수만 전달하며 Dockerfile이나 빌드 인자로 넣지 않는다. 실제 GMS를 호출하는 컨테이너는 외부 HTTPS 통신이 필요하므로 위 오프라인 명령의 `--network none`을 사용하지 않는다. 운영 데이터와 비밀정보를 이미지에 추가해 전달하지 않는다.
