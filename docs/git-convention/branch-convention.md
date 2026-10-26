# Branch Convention

본 문서는 프로젝트의 Git 브랜치 생성, 관리 및 병합 규칙을 정의한다.

## 1. Branch Strategy

프로젝트는 `main`과 `develop`을 중심으로 다음 브랜치 전략을 사용한다.

```text
main
└── develop
    ├── feature/...
    ├── fix/...
    ├── refactor/...
    ├── docs/...
    ├── test/...
    └── chore/...
```

### main

* 배포 및 시연 가능한 안정 버전을 관리한다.
* 직접 Push하지 않는다.
* `develop`에서 MR을 통해서만 변경한다.
* `develop → main` 병합 시 **Merge Commit**을 사용한다.

### develop

* 개발 통합 브랜치이다.
* 모든 작업 브랜치의 기준이 된다.
* 직접 Push하지 않는다.
* 작업 브랜치에서 MR을 통해서만 변경한다.

### Working Branch

* 실제 개발 작업을 수행하는 브랜치이다.
* 항상 최신 `develop`에서 생성한다.
* 브랜치 생성 직후 `develop`으로 **Draft MR**을 생성한다.
* 작업이 완료되면 Draft를 해제하여 정식 MR로 전환한다.
* `develop` 병합 시 **Squash Merge**를 사용한다.
* 병합 완료 후 브랜치를 삭제한다.

---

## 2. Protected Branch

`main`과 `develop`은 Protected Branch로 관리한다.

```text
main     → Direct Push 금지
develop  → Direct Push 금지
```

간단한 문서 수정, 설정 변경, 오타 수정 등의 작업도 직접 Push하지 않고 작업 브랜치를 생성한 뒤 MR을 통해 반영한다.

---

## 3. Branch Types

다음 6개의 브랜치 타입을 사용한다.

| Type       | 용도                  |
| ---------- | ------------------- |
| `feature`  | 새로운 기능 구현           |
| `fix`      | 버그 수정               |
| `refactor` | 기능 변경 없는 코드 구조 개선   |
| `docs`     | 문서 작성 및 수정          |
| `test`     | 테스트 코드 작성 및 수정      |
| `chore`    | 설정, 의존성, 빌드 등 기타 작업 |

`hotfix`, `release` 등의 별도 브랜치 타입은 사용하지 않는다.

긴급한 버그 수정도 `fix` 브랜치를 사용하며 최신 `develop`에서 생성한다.

---

## 4. Branch Naming

모든 작업 브랜치는 다음 형식을 사용한다.

```text
<type>/S15P21A207-<issue-number>-<description>
```

예시:

```text
feature/S15P21A207-123-payment-approval
fix/S15P21A207-124-cart-version-conflict
refactor/S15P21A207-125-payment-service
docs/S15P21A207-126-api-convention
test/S15P21A207-127-payment-approval
chore/S15P21A207-128-project-setup
```

### Naming Rules

* `<type>`은 정의된 Branch Type 중 하나를 사용한다.
* Jira 프로젝트 키는 `S15P21A207`을 사용한다.
* Jira 이슈 번호를 반드시 포함한다.
* `<description>`은 짧은 영어 `kebab-case`로 작성한다.
* description은 일반적으로 2~4단어 정도를 권장한다.
* Jira 이슈 제목과 Branch description이 문자열 기준으로 동일할 필요는 없다.
* Jira 이슈와 브랜치는 Jira Key로 연결한다.

예:

```text
Jira
S15P21A207-123 결제 승인 API 구현

Branch
feature/S15P21A207-123-payment-approval
```

---

## 5. One Issue, One Branch

원칙적으로 다음 관계를 유지한다.

```text
1 Jira Issue
    ↓
1 Working Branch
    ↓
1 Merge Request
    ↓
1 Squash Commit in develop
```

예:

```text
S15P21A207-123
↓
feature/S15P21A207-123-payment-approval
↓
Draft MR → MR
↓
develop
```

하나의 작업 브랜치에는 하나의 MR만 존재한다. 브랜치 생성 시점에 만든 Draft MR을 작업 완료 후 그대로 정식 MR로 전환하며, 작업 완료 시점에 MR을 새로 생성하지 않는다.

하나의 작업 브랜치에서 서로 독립적인 여러 Jira 이슈를 동시에 처리하지 않는다.

단, 현재 Jira 이슈의 작업 범위 안에서 발견된 사소한 수정은 별도의 Jira 이슈와 브랜치를 만들지 않고 해당 브랜치에서 함께 처리할 수 있다.

---

## 6. Creating a Branch

모든 작업 브랜치는 최신 `develop`을 기준으로 생성한다.

```bash
git checkout develop
git pull origin develop
git checkout -b feature/S15P21A207-123-payment-approval
git push -u origin feature/S15P21A207-123-payment-approval
```

브랜치를 원격에 Push한 직후 GitLab에서 해당 브랜치의 Draft MR을 생성한다. 세부 규칙은 아래 [10. Merge Request](#10-merge-request)를 따른다.

다른 작업 브랜치에서 새로운 작업 브랜치를 생성하지 않는다.

### 금지 예시

```text
develop
└── feature/A
    └── feature/B  ❌
```

### 올바른 예시

```text
develop
├── feature/A
└── feature/B
```

---

## 7. Dependencies Between Working Branches

작업 브랜치끼리 직접 의존하지 않는 것을 원칙으로 한다.

후속 작업이 다른 작업 브랜치의 코드를 필요로 한다면 선행 작업을 먼저 `develop`에 병합한다.

```text
feature/A
    ↓
MR + Squash Merge
    ↓
develop
    ↓
feature/B 생성
```

`feature/B`를 `feature/A`에서 생성하거나 `feature/A`를 직접 merge하여 사용하는 방식은 사용하지 않는다.

---

## 8. Synchronizing with develop

작업 중 `develop`에 새로운 변경사항이 반영되었다면 **Rebase** 또는 **Merge** 중 한 가지를 선택하여 최신 상태를 반영한다.

두 방식 모두 허용한다. 작업 브랜치는 `develop`에 **Squash Merge**로 병합되므로, 어떤 방식으로 동기화하더라도 `develop`의 이력은 Jira 이슈 단위로 유지된다.

### Rebase

작업 브랜치의 Commit을 최신 `develop` 위로 다시 쌓는다.

```bash
git fetch origin
git rebase origin/develop
```

충돌이 발생하면 충돌을 해결한 뒤:

```bash
git add .
git rebase --continue
```

이미 원격에 Push한 작업 브랜치를 Rebase했다면:

```bash
git push --force-with-lease
```

를 사용한다.

`--force`는 사용하지 않는다.

### Merge

`develop`을 작업 브랜치로 병합한다. 이미 Push한 Commit History가 변경되지 않으므로 Force Push가 필요하지 않다.

```bash
git fetch origin
git merge origin/develop
```

충돌이 발생하면 충돌을 해결한 뒤:

```bash
git add .
git commit
```

### 선택 기준

방식 선택은 작업자의 판단에 따르며, 다음을 참고한다.

* 아직 Push하지 않았거나 혼자 작업 중인 브랜치는 Rebase가 이력을 깔끔하게 유지한다.
* 이미 Push되어 다른 팀원이 참조 중이거나 Draft MR에서 리뷰가 진행 중인 브랜치는 Merge가 Force Push로 인한 혼란을 피할 수 있다.
* Commit 수가 많고 충돌이 반복되는 경우 Merge가 충돌 해결 비용이 낮다.

하나의 브랜치 안에서는 가급적 한 가지 방식을 일관되게 사용한다.

---

## 9. Push Rules

작업 브랜치는 개발 중에도 자유롭게 원격 저장소에 Push할 수 있다.

작업 내용 백업 및 팀원 간 진행 상황 공유를 위해 필요할 때 수시로 Push할 수 있다.

```bash
git push origin feature/S15P21A207-123-payment-approval
```

다른 팀원의 작업 브랜치에는 직접 Push하지 않는다.

Rebase로 이미 Push된 Commit History가 변경된 경우에만 `--force-with-lease`를 사용한다.

---

## 10. Merge Request

MR은 작업 완료 시점이 아니라 **작업 브랜치를 생성한 직후 Draft 상태로 먼저 생성**한다.

Draft MR은 작업 시작 시점부터 진행 상황과 변경 범위를 팀원에게 공유하고 CI 파이프라인을 조기에 확인하기 위해 사용한다.

작업이 완료되고 리뷰 가능한 상태가 되면 **새로운 MR을 만들지 않고 기존 Draft를 해제하여 정식 MR로 전환**한다.

```text
Jira Issue
    ↓
Working Branch 생성
    ↓
Push
    ↓
Draft MR 생성
    ↓
개발
    ↓
Commit / Push
    ↓
작업 완료
    ↓
Draft 해제 (정식 MR 전환)
    ↓
Review
    ↓
Squash Merge
    ↓
develop
```

Draft 상태의 MR은 병합하지 않는다. GitLab은 Draft MR의 Merge 버튼을 비활성화하므로 작업이 완료되지 않은 변경이 실수로 `develop`에 반영되는 것을 방지할 수 있다.

MR의 세부 작성 및 병합 규칙은 별도의 MR Convention을 따르고, 리뷰 필요 여부 판단 및 Reviewer 선정 기준은 Review Convention을 따른다.

---

## 11. Working Branch → develop

작업 브랜치를 `develop`에 반영할 때는 반드시 MR을 사용한다.

병합 방식은 **Squash Merge**를 사용한다.

```text
feature/S15P21A207-123-payment-approval
                    │
                    │ MR
                    │ Squash Merge
                    ▼
                 develop
```

작업 브랜치 내부에서는 작업 과정에 따라 여러 Commit을 생성할 수 있다.

```text
feature branch

commit A
commit B
commit C
commit D
```

`develop`에 병합할 때는 하나의 Jira 이슈 단위 Commit으로 Squash한다.

```text
develop

[S15P21A207-123] 결제 승인 기능 구현
```

따라서 `develop`에서는 Jira 이슈 단위로 변경 이력을 추적할 수 있도록 한다.

구체적인 Commit Message 형식은 별도의 Commit Convention에서 정의한다.

---

## 12. develop → main

`develop`은 배포 또는 시연 가능한 안정 버전이 완성되었을 때 `main`으로 병합한다.

단순히 기능 하나가 완료될 때마다 `main`으로 병합하지 않는다.

```text
develop
   │
   │ 배포/시연 가능한 버전 확정
   │ MR
   │ Merge Commit
   ▼
main
```

`develop → main`에서는 **Squash Merge가 아닌 Merge Commit**을 사용한다.

이를 통해 `develop`에 존재하는 Jira 이슈 단위 Squash Commit은 유지하면서 `main`에서는 배포 버전의 병합 경계를 확인할 수 있도록 한다.

---

## 13. Bug Fix

버그의 긴급도와 관계없이 `fix` 브랜치를 사용한다.

```text
develop
└── fix/S15P21A207-200-payment-error
```

버그 수정도 일반 작업과 동일하게 처리한다.

```text
develop 최신화
    ↓
fix 브랜치 생성
    ↓
Draft MR 생성
    ↓
수정
    ↓
Draft 해제
    ↓
Squash Merge
    ↓
develop
```

별도의 `hotfix` 브랜치는 사용하지 않는다.

`main`에서 직접 `fix` 브랜치를 생성하거나 `main`에 직접 수정사항을 반영하지 않는다.

---

## 14. Branch Deletion

작업 브랜치는 `develop` 병합이 성공하면 삭제한다.

```text
Working Branch
    ↓
MR
    ↓
develop
    ↓
Branch Delete
```

GitLab의 Merge 후 Source Branch 삭제 기능을 사용할 수 있다.

작업이 취소되었거나 더 이상 필요하지 않은 브랜치도 로컬 및 원격에서 정리한다.

필요해진 경우 기존의 오래된 브랜치를 재사용하기보다 Jira 이슈 상태를 확인하고 최신 `develop`을 기준으로 새 브랜치를 생성한다.

---

## 15. Complete Workflow

전체 브랜치 작업 흐름은 다음과 같다.

```text
1. Jira Issue 생성
        ↓
2. develop 최신화
        ↓
3. Working Branch 생성
        ↓
4. Push
        ↓
5. Draft MR 생성
        ↓
6. 개발
        ↓
7. Commit
        ↓
8. Push
        ↓
9. 필요 시 develop 동기화 (Rebase 또는 Merge)
        ↓
10. 작업 완료
        ↓
11. Draft 해제 (정식 MR 전환)
        ↓
12. Review
        ↓
13. Squash Merge → develop
        ↓
14. Working Branch 삭제
        ↓
15. 배포/시연 가능한 버전 완성
        ↓
16. MR + Merge Commit
        ↓
       main
```

### Example

```bash
# develop 최신화
git checkout develop
git pull origin develop

# 작업 브랜치 생성 및 원격 Push
git checkout -b feature/S15P21A207-123-payment-approval
git push -u origin feature/S15P21A207-123-payment-approval

# GitLab에서 Draft MR 생성

# 작업 후 Commit
git add .
git commit -m "..."

# 원격 Push
git push origin feature/S15P21A207-123-payment-approval

# develop 변경사항 반영이 필요한 경우 (Rebase 또는 Merge 중 선택)
git fetch origin
git rebase origin/develop

# 이미 Push한 브랜치를 Rebase했다면
git push --force-with-lease

# 또는 Merge로 동기화하는 경우 (Force Push 불필요)
git fetch origin
git merge origin/develop
git push origin feature/S15P21A207-123-payment-approval
```

작업 완료 후 GitLab에서:

```text
Draft MR
        ↓
Draft 해제 (정식 MR 전환)
        ↓
Review
        ↓
Squash Merge
        ↓
develop
        ↓
Source Branch Delete
```

배포 또는 시연 가능한 안정 버전이 완성되면:

```text
develop
   ↓
MR
   ↓
Merge Commit
   ↓
main
```
