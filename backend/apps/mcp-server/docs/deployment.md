# MCP 서버 배포 준비

S15P21A207-397. 이 문서는 Core·앱과 통합하기 위한 서버 배포 설정을 정리한다.
현재 Docker 이미지에는 로컬 QA 실행기·Mock 데이터·테스트 인증기가 포함되지 않는다.
운영 토큰 검증기와 Core 업무 어댑터는 아직 연결하지 않았으므로 환경변수만 설정해도
ChatGPT에서 QA4와 같은 기능이 열리는 상태는 아니다.

## 실행 방식과 환경파일

- 이미지: `backend/apps/mcp-server/Dockerfile`, 빌드 컨텍스트: `backend/`
- 실행: Java 21, 비루트 사용자, 컨테이너 포트 8080
- MCP: `/mcp`, 인증 metadata: `/.well-known/oauth-protected-resource/mcp`
- health: `/actuator/health` 및 probe endpoint. 기본 health 성공은 업무 연동 완료를 뜻하지 않는다.
- [`../.env.example`](../.env.example)을 참고해 서버 저장소 밖의 제한된 환경파일 또는
  인프라의 secret 관리 방식으로 컨테이너에 주입한다. 실제 값은 Git·이미지·MR에 넣지 않는다.
- Spring Boot는 `.env`나 `.env.local`을 자동으로 읽지 않는다. Compose의 `env_file` 또는
  명시적 `environment` 연결이 필요하다. Compose의 `--env-file`은 변수 치환 입력이며,
  그 안의 모든 값이 컨테이너로 전달되는 것은 아니다.
- 현재 공유 Compose는 MCP의 `CORE_SERVICE_URL`만 전달한다. 나머지 설정 전달은 인프라 담당 반영이 필요하다.
- QA 터널 스크립트는 기본 `.env.local`을 사용한다. `.env`로 바꾸려면 해당 스크립트의
  `-EnvFile`에 경로를 지정한다. 서버 배포용 환경파일과 QA 터널 키 파일을 구분한다.

예시는 의도적으로 인증 URL·scope·허용 목록을 비워 둔다. 합의되지 않은 값을 추측해서
채우지 않는다. Docker 컨텍스트에서는 모든 `.env` 및 `.env.*`와 `node_modules`를 제외한다.

## 기동 시 설정 검증

서버 통합 배포에는 `SPRING_PROFILES_ACTIVE=deployment`를 사용한다.

- issuer, `/mcp` resource, 전역 scope가 모두 있어야 한다.
- issuer와 resource는 HTTPS여야 한다. 기본 로컬 설정의 loopback HTTP 예외를 허용하지 않는다.
- `McpTokenVerifier` bean이 등록되어야 한다. 없으면 기동에 실패한다.
- 기존 설정 검증도 적용한다: Core origin 형식, 양수 timeout·크기 제한, 허용 Host 목록,
  활성화한 Tool의 scope·Core 어댑터 및 구매 인계 HTTPS 링크 조건.
- 시작 시 토큰 발급이나 검증 API 호출은 하지 않는다. 이 검사는 설정·구현 누락을 검출하며,
  제공자 연결·토큰의 유효성·업무 API 정상 동작은 통합 테스트로 확인해야 한다.

기본 프로필은 기존 smoke 검증을 위해 유지한다. 인증 미설정 시 기동은 가능하지만
`/mcp` 요청은 차단된다. 실제 통합 배포에서 `deployment`를 생략해 오류를 우회하지 않는다.
Tool은 기본 비활성이다. 해당 Core 어댑터를 구현하고 합의된 scope를 설정한 기능만 켠다.

## 토큰의 역할

| 구분 | 제공 위치 | 현재 상태 |
| --- | --- | --- |
| ChatGPT 사용자 MCP access token | OAuth 제공자가 발급, MCP HTTP Authorization Bearer 헤더 | Core 인증 계약 및 검증기 연결 필요 |
| MCP → Core 서비스 인증 | 합의된 서버 간 인증 방식으로 주입 | 계약 미확정, 임의 변수·전달 방식 추가 안 함 |
| `CONTROL_PLANE_API_KEY` | 로컬 Secure Tunnel 실행 프로세스 | 공개 HTTPS 직접 연결 시 MCP 컨테이너에는 불필요 |

사용자 access token을 고정 `.env` 값으로 공유하지 않는다. MCP 서버는 모델 자체를
호스팅하지 않으며 도구·화면을 제공한다. 이 서버 실행에 모델 호출용 OpenAI API 키를
추가할 필요는 없다. 사용자 연결 해제 후 access token 잔여 수명 허용 정책은 인증 제공자와
검증기 연동 작업에서 반영하며, 이번 배포 준비에서 기존 인증 의미를 변경하지 않는다.

## CI와 업데이트

1. 작업 브랜치에서 논리 단위로 커밋하고 `develop` 대상 MR을 만든다.
2. MR에서 `mcp-test`와 `mcp-ui-test`를 실행한다. UI는 lockfile 기반 `npm ci`,
   `npm test`, `npm run check` 순서다. CI에서 먼저 build하여 낡은 번들을 덮어쓰지 않는다.
3. 두 검사 통과 후 `mcp-build`가 JAR을 만든다. 공통 lint·deploy-smoke 규칙도 유지한다.
4. MR 병합 후 `develop` push 파이프라인에서 기존 `deploy-smoke`와 MCP 빌드에 이어
   커밋 SHA 태그의 Docker 이미지 빌드·서버 전송·개발 서버 배포가 실행된다.
5. 배포 후 health, 인증 거부·허용, 도구 목록, Core 호출, ChatGPT 새 대화의 UI를 확인한다.

작업 브랜치 수동 배포 Job은 없다. UI를 바꾼 개발자는 `ui/`에서 `npm run build` 후
생성된 resources HTML도 함께 커밋한다. Node는 CI 검증에 쓰고 런타임 컨테이너에는 넣지 않는다.
공개 주소는 유지하면서 서버 이미지를 교체한다. 도구 이름·schema·리소스 URI 변경 시에는
ChatGPT 연결의 도구 새로고침과 새 대화에서 확인한다. 이미 표시된 카드는 이전 UI일 수 있다.
세션은 현재 프로세스 메모리에 있으므로 재시작 후 재연결을 검증한다.

## 인프라 담당 반영 사항

- 고정 HTTPS 주소, 인증서, `/mcp`와 metadata 경로의 프록시 라우팅 제공
- MCP POST/GET/DELETE, Authorization·MCP 관련 헤더 및 스트리밍 응답이 통과하도록 설정
- 프록시가 전달하는 Host와 허용 Origin을 확정하고 환경변수 주입
- 서버 전용 환경파일/secret 접근 권한과 로그의 인증정보 마스킹 관리
- 기존 develop 자동 배포 Runner·SSH 변수·Docker 네트워크 및 Core 접근 확인
- CI Node 이미지 다운로드 및 npm 의존성 설치가 가능한 Runner 네트워크 확인

공유 Compose·Nginx·공통 배포 스크립트는 이 작업에서 변경하지 않는다.
운영 인증기와 Core 어댑터가 준비된 뒤 배포 프로필 활성화 및 실제 연결 검증을 함께 진행한다.
