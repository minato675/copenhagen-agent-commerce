# 문서 인덱스

| 문서 | 읽어야 하는 경우 |
| --- | --- |
| [팀 개발 및 기여 가이드](../CONTRIBUTING.md) | 처음 참여하거나 MR을 올릴 때 |
| [아키텍처](architecture/README.md) | 서비스 책임, 호출 방향, DB 소유권을 확인할 때 |
| [ADR](adr/README.md) | 중요한 기술 또는 경계 결정을 기록할 때 |
| [API](api/README.md) | 공개/내부 API 계약을 문서화할 때 |
| [MCP](mcp/README.md) | Agent-facing MCP surface를 변경할 때 |
| [채팅 중심 쇼핑 사용법](api/mcp-chat-shopping-481.md) | 상품 선택·장바구니·구매내역 조회와 재구매 방법을 확인할 때 |
| [구매내역 MCP 연동](api/mcp-purchase-history-495.md) | 구매내역 도구 계약과 배포 검증을 확인할 때 |
| [UCP](ucp/README.md) | UCP 계약이나 schema를 변경할 때 |
| [Payment Handler](payment-handler/README.md) | 결제 handler 계약을 변경할 때 |
| [Git과 Jira 연동 기록](git-convention/README.md) | 기존 Jira-GitLab 연동 테스트의 의미를 확인할 때 |
| [GitLab CI/CD](../infra/gitlab/README.md) | MR 검증 또는 EC2 자동 배포를 설정할 때 |
| [Docker/EC2](../infra/docker/README.md) | 로컬 환경이나 개발 EC2를 구성할 때 |

문서는 코드와 함께 변경합니다. API, 서비스 경계, 환경변수, DB schema가 바뀌었다면 같은 MR에서 관련 문서도 갱신하세요.
