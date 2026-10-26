# Commit Convention

## 1. Commit Message Format

커밋 메시지는 다음 형식을 따른다.

```text
<type>: [<Jira-key>] <description>
```

예시:

```text
feat: [S15P21A207-123] 결제 승인 API 구현
fix: [S15P21A207-124] 토큰 갱신 오류 수정
```

Jira에서 관리하지 않는 단순 작업은 Jira 키를 생략할 수 있다.

```text
chore: .gitignore 설정
docs: README 오타 수정
```

단, Jira에서 관리되는 작업은 반드시 해당 Jira 이슈 키를 포함한다.

커밋 메시지는 제목만 작성하며, 별도의 Body는 작성하지 않는다. 상세 내용은 Jira 또는 Merge Request에서 관리한다.

---

## 2. Commit Type

커밋 type은 다음 7가지를 사용한다.

| Type | 기준 |
|---|---|
| `feat` | 사용자 또는 시스템에 새로운 기능을 추가 |
| `fix` | 기존 기능의 오류를 수정 |
| `docs` | 문서만 변경 |
| `style` | 코드 동작에 영향을 주지 않는 형식 변경 |
| `refactor` | 기능 변화 없이 코드 구조를 개선 |
| `test` | 테스트 코드를 추가하거나 수정 |
| `chore` | 위 항목에 해당하지 않는 개발 환경, 설정, 패키지, 의존성 등의 작업 |

### `style`

`style`은 코드의 동작에 영향을 주지 않는 형식 변경에만 사용한다.

대상에는 다음과 같은 변경이 포함될 수 있다.

- 들여쓰기
- 공백
- 줄바꿈
- 세미콜론
- import 정리
- 포맷터 적용

CSS 또는 UI 디자인 변경을 의미하는 type으로 사용하지 않는다.

### `chore`

`chore`는 다른 type에 해당하지 않는 개발 환경 및 프로젝트 설정 관련 작업에 사용한다.

```text
chore: Spring Security 의존성 추가
chore: Docker Compose 설정 추가
chore: .gitignore 설정
```

여러 type에 해당하는 변경이 하나의 논리적 변경에 포함된 경우에는 변경의 **주목적을 기준으로 type을 선택**한다.

독립적인 논리적 변경이라면 각각 별도의 커밋으로 분리한다.

---

## 3. Jira Issue Key

Jira에서 관리되는 작업의 커밋에는 Jira 이슈 키를 포함한다.

Jira 이슈 키는 대문자로 작성하고 대괄호(`[]`)로 감싼다.

```text
feat: [S15P21A207-123] 결제 승인 API 구현
```

하나의 커밋에는 하나의 Jira 이슈 키만 사용한다.

여러 Jira 이슈에 해당하는 독립적인 변경사항이 포함되어 있다면 각각 별도의 커밋으로 분리한다.

Jira에서 관리하지 않는 단순 작업은 Jira 이슈 키를 생략할 수 있다.

```text
docs: README 오타 수정
```

---

## 4. Description

description은 **한글 명사형**으로 작성한다.

```text
feat: [S15P21A207-123] 결제 승인 API 구현
fix: [S15P21A207-124] 토큰 갱신 오류 수정
refactor: [S15P21A207-125] 결제 검증 로직 분리
```

문장 끝에는 마침표(`.`)를 사용하지 않는다.

파일명, 클래스명, 메서드명, 설정 파일명, API명, 기술명 등 코드 또는 기술 식별자는 실제 사용하는 표기를 유지한다.

```text
docs: [S15P21A207-123] branch-convention.md 작성
refactor: [S15P21A207-124] PaymentService 결제 검증 로직 분리
fix: [S15P21A207-125] application.yml 환경 변수 설정 오류 수정
```

---

## 5. Commit Unit

하나의 커밋은 **하나의 논리적 변경 단위**로 작성한다.

하나의 변경을 완성하기 위해 함께 필요한 여러 파일 또는 계층의 변경은 하나의 커밋에 포함할 수 있다.

예를 들어 하나의 기능 구현을 위해 Controller, Service, Repository, DTO가 함께 변경되는 경우 해당 변경들이 하나의 논리적 변경이라면 같은 커밋에 포함할 수 있다.

반대로 서로 독립적으로 의미를 가지는 변경은 별도의 커밋으로 분리한다.

---

## 6. Squash Commit

GitLab Merge Request에서 Squash Merge를 수행하는 경우 최종 Squash Commit도 본 커밋 컨벤션을 따른다.

Squash Commit의 type은 개별 커밋의 type을 나열하지 않고 **Merge Request 또는 Jira 작업 전체의 주목적을 대표하는 type**을 사용한다.

예를 들어 하나의 기능 개발 과정에서 `feat`, `test`, `refactor` 커밋이 함께 존재하더라도 Merge Request의 주목적이 기능 추가라면 최종 Squash Commit은 `feat`를 사용한다.

```text
feat: [S15P21A207-123] 결제 승인 기능 구현
```

---

## 7. Revert Commit

`git revert` 명령으로 자동 생성되는 Revert Commit 메시지는 본 커밋 메시지 형식의 예외로 허용한다.

```text
Revert "feat: [S15P21A207-123] 결제 승인 API 구현"
```

Revert Commit을 본 컨벤션에 맞추기 위해 임의로 메시지 형식을 변경하지 않는다.