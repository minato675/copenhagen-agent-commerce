# 게시 범위와 원본 기준

- 정리일: 2026-10-26
- 게시 대상: `minato675/copenhagen-agent-commerce` · **Private**
- 원본: [S15P21A207](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/tree/3e738c308cba2300a35041b75f467bafc54a7880)
- 기준 브랜치: `develop`
- 기준 커밋: `3e738c308cba2300a35041b75f467bafc54a7880`
- 접근 방식: 로그인된 Chrome에서 GitLab을 열고 Code → Download source code → ZIP으로 내려받았습니다.
- ZIP의 Git 커밋 comment와 브라우저에서 확인한 기준 커밋이 일치하는지 확인했습니다.

## 선별 방식

소스·테스트·설계 문서를 검토용으로 옮겼습니다. 원본 README는 `docs/source/README.original.md`에 보관하고,
루트 README에 실제 개인 담당·팀 기능·실행 의존성을 구분했습니다. 원본 README에는 이전 단계의 설명이나 제외 경로를 가리키는 링크가 남아 있을 수 있습니다.
원본 코드의 커밋 이력 전체를 이관하는 작업이 아니라 선별한 현재 소스의 새 스냅샷입니다. 원래 GitLab 저장소와 이력은 유지됩니다.

실제 `.env`, 키 파일, 설치 실행 파일·모델 가중치·대용량 생성 자료와 원래 팀의 에이전트·GitLab 메타데이터 일부는 게시에서 제외했습니다.
기존의 테스트용 문자열, 로컬 DB 예시 설정, 실행 시 생성하는 키의 PEM 형식 문자열은 운영 비밀키와 구분해 검토했습니다.

| 제외 사유 코드 | 파일 수 |
| --- | ---: |
| `auxiliary-package-or-generated-catalog-data` | 42 |
| `binary-model-installer-media-or-key-material` | 2 |
| `large-artifact-over-5-MiB` | 2 |
| `original-team-agent-or-workflow-metadata` | 4 |

선별한 원본 파일은 **1877개**이며, 파일별 경로·원본 SHA256과 제외 사유는 [manifest](EXPORT_MANIFEST.json)에 있습니다.
문서 작성 후 원본 파일의 바이트 일치, Python 구문과 JSON 형식, GitHub에 게시한 파일과 공개 범위를 검증합니다.
전체 서비스 실행·유료 API·실기·외부 배포를 이번 정리에서 새로 검증했다고 주장하지 않습니다.
