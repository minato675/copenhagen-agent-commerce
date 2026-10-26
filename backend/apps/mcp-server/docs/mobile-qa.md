# MCP 모바일 QA — Story 362

첫 단계는 ChatGPT 연결과 최소 UI이다. 검색·장바구니·앱 결제 인계·결과 조회는 각각 후속 Story에서 추가한다.
실기기: Galaxy S25 Ultra, ChatGPT Android 앱. 자동 검증이 통과해도 사용자가 확인하기 전에는 Story를 완료 처리하지 않는다.

## 구현 경계

- 운영 코드: 인증된 요청에서 `open_copenhagen`, `check_connection`과 버전이 붙은 HTML 리소스를 제공한다.
- `MCP_UI_ENABLED` 기본값은 false이다. UI를 켜도 기존 인증 필터/검증기를 우회하지 않는다.
- 로컬 QA 실행기와 미리보기는 `src/test`에만 있다. 운영 `bootJar`에는 들어가지 않는다.
- QA에서는 프로세스 안에서 생성한 4시간 신원을 사용한다. 사용자 계정·Core·상품·결제에 접근하지 않는다.
- QA 실행기는 127.0.0.1:8091에만 바인딩하며, 외부 접근은 사용자의 개인 Secure MCP Tunnel을 통해 진행한다.
- 실제 Core 인증은 366, 실제 판매처/상품 어댑터는 367에서 따로 검증한다.

## 실행

Java 21을 `JAVA_HOME`으로 지정하거나 `start-qa.ps1 -JavaHome <JDK 경로>`를 사용한다.
Windows CMD에서 저장소 루트 기준:

```bat
backend\apps\mcp-server\scripts\start-qa.cmd
```

미리보기: http://127.0.0.1:8091/qa — 버튼도 실제 HTTP MCP initialize/tools/call을 거친다.
MCP: http://127.0.0.1:8091/mcp
로그와 프로세스 정보는 이 worktree의 `backend/apps/mcp-server/build/qa`에만 저장된다.
기존 Lab의 8081/8084 및 공유 원본 저장소는 사용하지 않는다.

종료:

```powershell
& .\backend\apps\mcp-server\scripts\stop-qa.ps1
```

종료 스크립트는 기록된 PID와 시작 시간이 모두 일치하는 자신의 프로세스만 종료한다.
UI 변경 후 `ui` 디렉터리에서 `npm ci`, `npm test`, `npm run build`, `npm run check`를 실행하고 QA 서버를 다시 시작한다.

## ChatGPT 연결

1. [OpenAI 터널 설정](https://platform.openai.com/settings/organization/tunnels)에서 개인 QA 터널을 생성하고 기존 개인 ChatGPT 작업공간과 연결한다.
2. [공식 tunnel-client](https://github.com/openai/tunnel-client/releases/latest)를 설치한다. 기존 공식 설치본도 사용할 수 있다.
3. PowerShell에서 아래 명령을 실행한다. 실제 터널 ID와 클라이언트 경로는 로컬 값이며 Git에 넣지 않는다.

```powershell
& .\backend\apps\mcp-server\scripts\start-qa-tunnel.ps1 -TunnelId <QA 터널 ID> -ClientPath <tunnel-client.exe 경로>
```

4. `Runtime API key`에 Tunnels Read + Use 권한 키를 콘솔에서 직접 입력한다. 채팅에 입력하거나 파일로 저장하지 않는다.
5. ChatGPT 플러그인 설정에서 이름 `Copenhagen QA1`, 연결 유형 Tunnel, 해당 QA 터널, 인증 없음으로 연결한다.
   이 설정은 계정 데이터가 없는 격리된 QA 런타임에만 적용한다.
6. 새 채팅에서 `Copenhagen QA1`을 선택하고 `코펜하겐 열어줘`라고 요청한다.
7. 화면 하단의 QA1 버전 앞 8자리가 실행 콘솔의 uiVersion 앞 8자리와 일치하는지 확인한다.

기존 Copenhagen 쇼핑 Lab 플러그인과 QA1을 혼용하지 않는다. 이전 카드의 리소스는 캐시될 수 있으므로 UI 수정 후 새 카드를 연다.
키 입력·터널 연결·ChatGPT 실제 렌더링은 자동 테스트와 별개의 확인 항목이다.
QA 서버와 터널 콘솔을 켜둔 상태에서 휴대폰으로 확인한다. 프로세스의 QA 신원은 4시간 뒤 만료되므로
장시간 뒤 다시 확인할 때는 `stop-qa.ps1` → `start-qa.cmd`로 서버를 재시작하고 새 카드를 연다.

## 사용자 QA 체크리스트

| 절차 | 기대 결과 |
| --- | --- |
| 새 채팅에서 “코펜하겐 열어줘” | 최소 카드1개, 반복 설명 여부 확인 |
| 연결 확인 버튼 누르기 | 연결됨과 서버 확인 시간이 갱신됨 |
| 다시 누르기 | 같은 카드에서 새 응답, 카드가 추가되지 않음 |
| 홈으로 나간 후 ChatGPT 복귀 | 버튼을 다시 사용할 수 있음 |
| 새 대화에서 다시 열기 | 같은 QA 버전의 새 카드가 열림 |
| 서버 중단 후 버튼, 서버 재시작 후 재시도 | 10초 이내 오류 표시 후 복구 가능 |

모바일 호스트 fullscreen을 요청하지 않는다. 텍스트 크기 확대·기기 화면 설정에 따른 잘림도 확인한다.
호스트가 최종 답변을 생성하므로 UI 아래 텍스트를 항상 숨긴다고 보장하지 않는다. 실제 관찰 결과를 이슈에 남긴다.

## 개발 검증

```powershell
cd backend
.\gradlew.bat :apps:mcp-server:test :apps:mcp-server:checkstyleMain :apps:mcp-server:checkstyleTest :apps:mcp-server:bootJar
```

MCP 리소스/도구 계약, 인증 회귀, UI 두 호스트 경로·잘못된 응답·timeout/재시도, QA 프리뷰의 실제 MCP 왕복을 검증한다.
프로덕션 JAR에서 `com/copenhagen/mcp/qa`와 Fake 검증기가 제외됐는지도 확인한다.

## QA1 검증 기록 — 2026-09-16

대상 UI 버전: `9a4ee786d1263a24` (카드 표시: `9a4ee786`).

| 구분 | 결과 |
| --- | --- |
| Java / Checkstyle / bootJar | Java 테스트 145개, Checkstyle main/test, bootJar 통과 |
| 운영 산출물 | UI 리소스 포함, QA 클래스·Fake 검증기 제외 확인 |
| UI | Node 테스트 6개 및 생성 HTML 일치 검사 통과 |
| 반응형 미리보기 | 320px·412px에서 가로 넘침 없음, 버튼 높이 57px |
| 실행기 | PowerShell 구문 검사 및 QA 서버 시작/종료/재시작 확인 |
| ChatGPT 웹 | Copenhagen QA1 설치, 최초 요청으로 카드 1개 표시 및 버전 일치 확인 |
| 위젯 MCP 호출 | 연결 확인 버튼으로 서버 시간이 16:56:59 → 16:57:35 KST 갱신됨. 카드 추가 없음 |
| 답변 텍스트 | 이번 웹 호출에서는 UI 아래 추가 설명 없음. 모든 호출/호스트의 동작 보장은 아님 |
| S25 Ultra / ChatGPT Android | 사용자 확인: 카드 표시, 버튼 반복 호출, 홈/앱 복귀, 새 대화, 화면 잘림 여부 5개 항목 모두 정상. Task 364 완료 |
| 서버 중단 및 재시작 복구 | 사용자 S25 확인: 10초 안에 오류 표시 및 재시도 가능, 재시작 후 기존 카드에서 연결됨과 시간 갱신 정상. Task 365 및 Story 362의 QA 통과 |

### 재연결 및 모바일 확인 — 2026-09-16

- QA 서버와 터널 재연결 후 ChatGPT 웹 카드의 연결 확인 버튼으로 서버 시간이 20:09:49 → 20:10:18 KST로 갱신됐다.
- 카드의 UI 버전은 `9a4ee786`이며, 동일 카드에서 연결됨으로 복구되는 것을 확인했다.
- 사용자가 S25 Ultra의 ChatGPT Android에서 카드 표시, 반복 호출, 앱 복귀, 새 대화, 화면 잘림 여부를 확인하고 5개 항목 모두 정상으로 보고했다.
- QA 서버만 종료하고 터널을 유지한 상태에서 사용자가 S25 기존 카드의 버튼을 눌러 10초 안에 오류 표시 및 재시도 가능 상태를 확인했다.
- QA 서버 재시작 후 사용자가 같은 S25 카드에서 재시도해 연결됨과 서버 시간 갱신을 확인했다. 웹 기존 카드도 오류 상태에서 복구됐으며 확인 시간은 20:17:39 KST, 버전은 `9a4ee786`이었다.
- Task 363의 개발 검증, Task 364의 모바일 UI 검증, Task 365의 실행·복구 검증을 완료했다. Story 362의 사용자 QA 수용 기준을 통과했으며 후속 기능은 별도 Story에서 진행한다.

검증은 격리된 연결 확인 런타임을 대상으로 했다. 실제 계정 인증·상품·결제 연동 검증은 포함하지 않는다.
개인 ChatGPT 대화 주소와 터널 식별자는 공용 Git 문서에 저장하지 않는다.

공식 문서: [MCP UI](https://developers.openai.com/plugins/build/chatgpt-ui), [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels).
