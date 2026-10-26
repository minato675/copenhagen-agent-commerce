# Directory Convention

프로젝트의 디렉터리 구조와 파일 배치 기준을 정의한다.

---

## 1. 기본 구조

```text
/
├── ai/
├── backend/
├── docs/
├── frontend/
├── infra/
├── mobile/
├── scripts/
├── .gitignore
├── README.md
└── docker-compose.yml
```

| 경로 | 역할 |
|---|---|
| `ai/` | AI 관련 서비스 및 코드 |
| `backend/` | 백엔드 애플리케이션 |
| `frontend/` | 웹 프론트엔드 애플리케이션 |
| `mobile/` | 모바일 애플리케이션 |
| `infra/` | 배포 및 인프라 구성 |
| `scripts/` | 개발·배포 자동화 스크립트 |
| `docs/` | 프로젝트 공통 문서 |

---

## 2. Backend

백엔드는 현재의 Gradle 멀티모듈 구조를 유지한다.

```text
backend/
├── apps/
├── config/
├── gradle/
├── libs/
├── mocks/
├── build.gradle
└── settings.gradle
```

- `apps/`: 실행 가능한 애플리케이션 모듈
- `libs/`: 여러 애플리케이션에서 공유하는 코드
- `mocks/`: Mock 서비스 및 테스트용 구현
- `config/`: 공통 빌드·품질 설정

---

## 3. Documentation

프로젝트 문서는 `docs/`에 배치한다.

문서는 주제별 디렉터리로 구분한다.

```text
docs/
├── adr/
├── api/
├── architecture/
├── git-convention/
├── mcp/
├── payment-handler/
└── ucp/
```

프로젝트 전체에 적용되는 단일 문서는 `docs/` 바로 아래에 둘 수 있다.

예:

```text
docs/
├── AGENTS.md
├── project-overview.md
├── branch-convention.md
├── commit-convention.md
├── mr-convention.md
└── directory-convention.md
```

같은 내용을 여러 디렉터리에 중복 작성하지 않는다.

---

## 4. Infrastructure

```text
infra/
├── docker/
├── gitlab/
├── monitoring/
└── nginx/
```

---

## 5. Scripts

특정 서비스에서만 사용하는 스크립트는 가능한 한 해당 서비스 내부에 둔다.

---

## 7. 디렉터리 추가

새로운 최상위 디렉터리는 기존 디렉터리의 책임으로 표현할 수 없는 경우에만 추가한다.
