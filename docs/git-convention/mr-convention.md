# Merge Request Convention

본 문서는 프로젝트의 Merge Request 작성, 리뷰 및 병합 규칙을 정의한다.

## 1. 기본 원칙

모든 변경사항은 작업 브랜치에서 작업한 뒤 MR을 통해 `develop`에 반영한다.

`develop` 브랜치에 직접 Push하지 않는다.

브랜치 생성 및 병합 전략에 관한 세부 규칙은 `branch-convention.md`를 따른다.

리뷰 필요 여부 판단 및 Reviewer 선정 기준은 `review-convention.md`를 따른다.

---

## 2. MR 생성 시점

MR은 작업 완료 시점이 아니라 **작업 브랜치를 생성한 직후 Draft 상태로 먼저 생성**한다.

```text
Working Branch 생성 / Push
    ↓
Draft MR 생성
    ↓
개발 (Commit / Push)
    ↓
작업 완료
    ↓
Draft 해제 (정식 MR 전환)
    ↓
Review
    ↓
Squash Merge
```

작업이 완료되면 **새로운 MR을 만들지 않고 기존 Draft MR을 그대로 정식 MR로 전환**한다.

하나의 작업 브랜치에는 하나의 MR만 존재한다.

### Draft MR 생성

작업 브랜치를 원격에 Push한 직후 `develop`을 대상으로 Draft MR을 생성한다.

GitLab에서는 MR 제목 앞에 `Draft:` 접두사를 붙이거나 MR 생성 화면의 **Mark as draft** 옵션을 사용한다.

```text
Draft: feat: [S15P21A207-123] 결제 승인 API 구현
```

Draft MR 생성 시점에는 다음 사항을 확인한다.

- Source Branch와 Target Branch(`develop`)가 올바른가
- 제목이 MR 제목 형식을 따르는가
- 본문에 작업 예정 범위가 작성되었는가
- Squash 및 Source Branch 삭제 옵션이 설정되었는가

### Draft 해제

작업이 완료되고 리뷰 가능한 상태가 되면 Draft를 해제한다.

GitLab MR 화면의 **Mark as ready** 버튼을 사용하거나 제목에서 `Draft:` 접두사를 제거한다.

Draft 해제 전 다음 사항을 확인한다.

- 작업 범위가 완료되었는가
- 본문의 작업 내용과 테스트 항목이 실제 변경사항에 맞게 최신화되었는가
- 불필요한 디버깅 코드나 임시 코드가 제거되었는가
- 관련 없는 변경사항이 포함되지 않았는가
- 로컬에서 정상 동작하는가

---

## 3. MR 제목

MR 제목은 다음 형식을 사용한다.

```text
<type>: [<Jira-key>] <description>
```

예시:

```text
feat: [S15P21A207-123] 결제 승인 API 구현
fix: [S15P21A207-124] 장바구니 조회 오류 수정
```

`type`, Jira Key 및 description 작성 방식은 `commit-convention.md`를 따른다.

Draft 상태에서는 위 형식 앞에 `Draft:` 접두사를 붙인다.

```text
Draft: feat: [S15P21A207-123] 결제 승인 API 구현
```

Draft를 해제하면 `Draft:` 접두사가 제거되어 정식 MR 제목이 된다.

---

## 4. MR 본문

MR 본문에는 다음 두 항목을 작성한다.

```markdown
## 작업 내용

- 구현하거나 수정한 주요 내용을 작성한다.

## 테스트

- 수행한 테스트 또는 확인 방법을 작성한다.
```

예시:

```markdown
## 작업 내용

- 결제 승인 API 구현
- 승인된 Checkout에 대한 결제 요청 처리
- 중복 요청 방지를 위한 멱등성 처리 추가

## 테스트

- 정상 결제 승인 요청 확인
- 동일 요청 재전송 시 중복 결제가 발생하지 않는 것 확인
```

리뷰 시 확인이 필요한 사항이나 관련 문서 등 추가 정보가 있다면 필요한 항목을 자유롭게 추가할 수 있다.

Draft MR을 생성하는 시점에는 작업 예정 범위를 기준으로 본문을 작성하고, 작업이 진행되면서 실제 변경사항에 맞게 갱신한다.

Draft를 해제하기 전에 본문의 작업 내용과 테스트 항목을 최종 상태로 정리한다.

---

## 5. Assignee / Reviewer

Assignee는 MR 작성자 본인으로 지정하며 Draft MR 생성 시점에 지정한다.

Reviewer 지정 여부는 변경사항의 영향 범위를 기준으로 결정한다.

Reviewer는 Draft 해제 시점에 지정한다. Draft 상태의 MR에는 리뷰를 요청하지 않는다.

다른 팀원의 개발 영역에 영향을 주는 변경은 Reviewer를 최소 1명 지정하고, 개인 담당 영역 안에서 독립적으로 결정 가능한 변경은 Reviewer를 지정하지 않고 Self Merge할 수 있다.

Self Merge 가능 조건과 Reviewer 선정 기준은 `review-convention.md`를 따른다.

---

## 6. 리뷰 및 수정

리뷰 과정에서 수정이 필요한 경우 기존 작업 브랜치에서 수정한 뒤 다시 Push한다. 이때 MR을 새로 생성하지 않는다.

Reviewer가 필요한 MR은 리뷰가 완료된 후 병합한다.

개인 담당 영역에서 독립적으로 결정 가능한 MR은 리뷰 없이 Self Merge할 수 있다.

리뷰 진행에 관한 세부 규칙은 `review-convention.md`를 따른다.

---

## 7. Merge 조건

모든 MR은 다음 조건을 만족한 후 병합한다.

- Draft가 해제된 상태
- 작업이 완료된 상태
- 필요한 테스트가 완료된 상태
- Merge Conflict가 없는 상태

Draft 상태의 MR은 병합하지 않는다.

Reviewer가 필요한 MR은 추가로 다음 조건을 만족해야 한다.

- Reviewer의 리뷰가 완료된 상태
- 주요 리뷰 의견이 해결된 상태

---

## 8. Conflict 처리

Merge Conflict가 발생한 경우 MR 작성자가 해결한다.

구체적인 `develop` 동기화 및 Conflict 해결 방식은 `branch-convention.md`를 따른다.