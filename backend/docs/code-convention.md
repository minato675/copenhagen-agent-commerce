# Backend Conventions

백엔드에서 Java 코드를 작성할 때 적용하는 공통 규칙입니다.

현재 프로젝트의 Java 21, Spring Boot, JdbcTemplate, PostgreSQL 및 기존 EditorConfig·Checkstyle 설정을 기준으로 작성했습니다. 패키지 구조와 서비스 경계는 기존 문서를 따르고, 이 문서는 코드 작성 방식에 집중합니다.

> **정정 이력 (2026-09-09):** [S15P21A207-211]에서 이 문서를 "기본 데이터 접근 기술은 Spring Data JPA"로 수정했으나,
> 실제 구현된 서비스(`pay-service` 전체, `core-service`)는 JPA 의존성과 Entity가 전혀 없고 100% `JdbcTemplate` +
> Repository 인터페이스 패턴을 사용 중이었습니다. [S15P21A207-210] 작업 중 이 불일치를 확인했고, 문서를 실제 코드
> 기준(JdbcTemplate)으로 되돌립니다. JPA 도입은 실제 도입 시점에 별도 팀 논의와 문서 수정으로 다시 결정합니다.

- 상태: 팀 검토용 초안
- 적용 대상: `backend/apps`, `backend/mocks`, `backend/libs`의 Java 코드
- 기본 패키지: `com.copenhagen`
- 관련 문서: [디렉터리 규칙](directory-convention.md), [팀 개발 가이드](../CONTRIBUTING.md)

새로 제안한 규칙은 팀 합의 후 적용합니다. 아래의 제안이 모두 현재 자동 검사에 반영되어 있다는 의미는 아닙니다.

## CONTRIBUTING.md와의 관계

이 문서는 [CONTRIBUTING.md](../CONTRIBUTING.md)를 기준으로 코드 작성 세부 규칙을 보완합니다. 기존 개발 절차와 서비스 책임을 재정의하지 않습니다. 내용이 충돌하면 이 문서를 근거로 기존 규칙을 바꾸지 않고, 충돌을 공유한 뒤 팀 결정에 맞춰 이 문서를 정정합니다.

| 주제 | 기준 문서의 항목 | 이 문서에서 보완하는 내용 |
| --- | --- | --- |
| 작업 위치·공유 범위 | 2. 작업 위치 / 3. 모듈 경계 | DTO·import·공통 예외 코드 작성 |
| 서비스 간 계약 | 4. 계약 변경 순서 | DTO 네이밍·변환·검증 |
| DB | 5. DB 변경 규칙 | SQL 작성·마이그레이션 작성 및 검증 |
| 설정·비밀정보 | 6. 설정과 비밀정보 | 설정값 사용·민감정보 로깅 방지 |
| 테스트·검증 | 8. 개발 중 검증 / 9. 테스트 위치 | 테스트 이름·fixture·시간·동시성 검증 |
| 브랜치·MR·배포 | 7. 브랜치 / 10~12. MR·Merge 이후 | 별도 규칙을 추가하지 않고 원문 준수 |

작업 전에 책임 모듈의 README를 확인합니다. 다른 애플리케이션의 구현 코드를 import하거나 Gradle dependency로 추가하지 않으며, 서비스 간 호출은 공유 계약을 기준으로 HTTP를 사용합니다.

## 코드 포맷

| 항목 | 규칙 |
| --- | --- |
| 인코딩 | UTF-8 |
| 줄바꿈 | LF |
| 들여쓰기 | 공백 4칸. 탭 사용 금지 |
| 이어지는 줄 | 기본 들여쓰기에서 공백 8칸 추가 |
| 한 줄 길이 | 최대 120자. 기존 Checkstyle의 package·import·URL 예외 유지 |
| 중괄호 | 여는 중괄호는 선언과 같은 줄에 작성 |
| 조건문·반복문 | 본문이 한 줄이어도 중괄호 사용 |
| 문장 | 한 줄에 한 문장 |
| 공백 | 쉼표 뒤, 이항 연산자 양옆, 제어문 키워드 뒤에 공백 |
| 빈 줄 | 메서드 사이 한 줄. 메서드 안에서는 처리 단계 구분에 사용 |
| 파일 끝 | 마지막 개행 한 개 유지 |

긴 인자 목록은 인자마다 줄을 나눕니다. 닫는 소괄호는 마지막 인자 줄에 둡니다.

```java
public ApprovalService(
        ApprovalRepository approvalRepository,
        Clock clock) {
    this.approvalRepository = approvalRepository;
    this.clock = clock;
}
```

```java
if (approval.isExpired(now)) {
    throw new BusinessException(ApprovalErrorCode.APPROVAL_EXPIRED);
}
```

길이 제한을 맞추기 위해 메서드나 변수 이름을 의미 없는 약어로 줄이지 않습니다.

## Import

- 와일드카드 import를 사용하지 않습니다. static import에도 동일하게 적용합니다.
- 사용하지 않는 import와 중복 import를 제거합니다.
- 일반 import를 전체 경로 기준 사전순으로 작성합니다.
- 일반 import 다음 빈 줄 한 줄을 두고 static import를 사전순으로 작성합니다.
- 같은 단순 이름의 클래스가 충돌할 때만 필요한 위치에 전체 클래스 경로를 사용합니다.

```java
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
```

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 클래스·인터페이스·record·enum 타입 | PascalCase | `ApprovalService`, `PaymentStatus` |
| 메서드 | camelCase. 수행 동작이 드러나는 이름 | `approvePayment`, `findById` |
| 변수·필드·파라미터 | camelCase. 의미 있는 명사 | `paymentId`, `expiresAt` |
| 상수·enum 값 | UPPER_SNAKE_CASE | `MAX_PIN_ATTEMPTS`, `APPROVED` |
| 패키지 | 소문자. 하이픈·언더스코어 금지 | `com.copenhagen.pay` |
| 요청 DTO | Request 접미사 | `CreateApprovalRequest` |
| 응답 DTO | Response 접미사 | `ApprovalResponse` |
| 예외 | Exception 접미사 | `BusinessException` |
| 테스트 클래스 | 대상 이름 + Test 또는 IntegrationTest | `ApprovalServiceTest` |

- 약어는 일반 단어처럼 표기합니다: `paymentId`, `PgClient`, `ApiResponse`.
- `req`, `res`, `obj`, `tmp` 대신 `request`, `response`, 역할이 드러나는 이름을 사용합니다.
- 반복문의 단순 인덱스는 `i`, `j`를 사용할 수 있습니다.
- boolean 메서드는 `isExpired()`, `hasPaymentMethod()`, `canApprove()`처럼 판단 내용이 드러나게 작성합니다.
- boolean 필드·record 컴포넌트는 `expired`, `defaultMethod`처럼 긍정형을 우선합니다.
- 기존 외부 API에 `isDefault` 등 합의된 이름이 있다면 계약을 유지합니다.
- 컬렉션은 복수형으로 작성합니다: `payments`, `paymentIds`.
- 시간과 단위는 이름으로 구분합니다: `expiresAt`, `amountMinor`, `timeoutMillis`.
- `findById`는 부재를 반환할 수 있는 조회, `getById`는 부재 시 예외를 던지는 조회에 사용합니다.

## 클래스·메서드 작성

클래스 내부는 다음 순서로 배치합니다.

1. 상수·static 필드
2. 인스턴스 필드
3. 생성자
4. 정적 팩토리 메서드
5. 공개 메서드
6. 내부 보조 메서드

- 필드는 기본적으로 `private`으로 선언합니다.
- 외부에 필요한 메서드만 `public`으로 공개합니다.
- 의존성이나 생성 이후 바뀌지 않는 필드는 `final`로 선언합니다.
- 로컬 변수는 명시적 타입을 사용합니다. 초안에서는 `var`를 사용하지 않습니다.
- 재정의 메서드는 `@Override`를 붙입니다.
- 의미 없는 `Manager`, `Helper`, `Processor` 대신 실제 책임이 드러나는 이름을 사용합니다.
- 단일 구현 Service에 인터페이스와 `ServiceImpl`을 기계적으로 만들지 않습니다.
- 메서드는 한 가지 작업을 표현하고, 서로 다른 검증·변환·처리가 길게 섞이면 의미 있는 보조 메서드로 분리합니다.
- 줄 수나 파라미터 개수만으로 강제 분리하지 않습니다. 같은 목적의 인자가 반복되면 객체로 묶는 것을 검토합니다.

## 생성자·의존성 주입

- Spring 컴포넌트는 생성자 주입을 사용합니다.
- 의존 필드는 `private final`로 선언합니다.
- 필드 주입과 setter 주입은 사용하지 않습니다.
- 생성자가 하나라면 `@Autowired`를 생략합니다.
- 현재 Lombok 의존성이 없으므로 생성자를 직접 작성합니다.
- Lombok 도입 여부는 팀 합의로 결정하고, 도입 전에는 Lombok 애너테이션을 코드 예제의 전제로 삼지 않습니다.

```java
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;

    public ApprovalService(ApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }
}
```

## DTO

- 단순 요청·응답 DTO는 불변 `record`를 기본으로 사용합니다.
- 상태 변경이 필요한 업무 객체에는 `record`를 일괄 적용하지 않습니다.
- 요청 DTO와 응답 DTO를 구분합니다.
- DB 모델을 그대로 API 응답으로 반환하지 않습니다.
- 공유 계약 DTO는 특정 서비스의 내부 모델을 import하지 않습니다.
- 공유 DTO의 위치와 제공자·소비자 변경 순서는 CONTRIBUTING.md의 4항을 따릅니다. 계약 모듈에 Controller·HTTP client·Repository·업무 Service 구현을 넣지 않습니다.
- DTO 변환은 서비스 내부의 정해진 변환 메서드나 Mapper에 모읍니다.
- 단순 변환 하나를 위해 Mapper 인터페이스와 구현체를 항상 만들지는 않습니다.
- `record`의 불변성은 내부 컬렉션까지 보장하지 않습니다. 수정 방지가 필요하면 방어적 복사를 합니다.

```java
public record ApprovalListResponse(List<ApprovalResponse> items) {

    public ApprovalListResponse {
        items = List.copyOf(items);
    }
}
```

PIN·토큰 같은 민감 필드가 있는 DTO는 자동 생성된 `toString()`도 원문을 출력할 수 있습니다. 요청 DTO 전체를 로그에 출력하지 않으며, 필요하면 민감 필드를 제외한 `toString()`을 직접 작성합니다.

## 요청 검증

- 요청 형식 검증과 업무 조건 검증을 구분합니다.
- 길이·필수 입력·숫자 범위는 요청 경계에서 검증합니다.
- 승인 가능 상태·소유권·만료 여부는 업무 처리에서 검증합니다.
- Bean Validation을 도입하는 모듈은 검증 의존성을 명시하고, DTO 컴포넌트에 제약을 선언한 뒤 Controller에서 `@Valid`로 검증합니다.
- 애너테이션만 붙였다고 검증이 실행된다고 가정하지 않습니다.
- 아래 예제는 검증 의존성을 추가한 뒤 적용하는 작성 형태입니다.

```java
public record SelectPaymentMethodRequest(
        @NotNull UUID paymentMethodId) {
}
```

## 조건문·반복문·Stream

- 실패 조건을 먼저 처리하고 정상 흐름의 중첩을 줄입니다.
- 조건이 길어지면 의미 있는 boolean 변수나 메서드로 분리합니다.
- 이중 부정과 중첩 삼항 연산자를 피합니다.
- 삼항 연산자는 짧고 단순한 값 선택에만 사용합니다.
- Stream은 변환·필터·집계에 사용합니다.
- DB 변경, 외부 API 호출, 여러 단계 예외 처리가 섞이면 반복문을 우선합니다.
- Stream의 `peek`에 업무 상태 변경을 넣지 않습니다.
- `parallelStream()`은 기본적으로 사용하지 않습니다. 필요성과 실행 환경을 검토한 경우만 사용합니다.

```java
List<UUID> activePaymentIds = payments.stream()
        .filter(Payment::isActive)
        .map(Payment::id)
        .toList();
```

## null·Optional·컬렉션

- 컬렉션 반환값에는 `null` 대신 빈 컬렉션을 사용합니다.
- 단건 조회의 부재가 정상 결과라면 반환 타입에 `Optional<T>`를 사용할 수 있습니다.
- `Optional`은 기본적으로 메서드 반환 타입에만 사용합니다.
- 필수 입력을 `Optional`로 감싸서 검증을 생략하지 않습니다.
- `Optional.get()`을 무조건 호출하지 않습니다. `orElseThrow`, `map` 등을 사용합니다.
- 외부 응답 DTO의 nullable 필드는 API 명세에 명시합니다.
- 빈 문자열, 숫자 0, 임의 UUID를 부재 값으로 대신 사용하지 않습니다.
- 컬렉션 선언 타입은 `List`, `Set`, `Map`을 사용하고 구현체가 꼭 필요한 경우만 구체 타입을 드러냅니다.

## 문자열·enum·상수

- 문자열 값은 `==`로 비교하지 않습니다.
- nullable 문자열은 `Objects.equals` 또는 상수 문자열의 `equals`로 비교합니다.
- enum 값은 `==`로 비교합니다.
- 상태는 가능한 한 enum으로 표현합니다.
- enum의 `ordinal()`을 API 값이나 DB 상태값으로 저장하지 않습니다.
- 의미 있는 업무 숫자·문자열은 이름 있는 상수로 추출합니다.
- 환경마다 달라지는 값은 상수로 고정하지 않고 설정으로 받습니다.

```java
private static final int MAX_PIN_ATTEMPTS = 5;

if (payment.status() == PaymentStatus.UNKNOWN) {
    return;
}
```

위 숫자는 코드 형태를 보여주는 예시이며 실제 PIN 정책값을 확정하는 규칙이 아닙니다.

## 금액·날짜·시간

- 결제 금액은 최소 화폐 단위 정수와 통화를 함께 표현합니다.
- Java의 최소 화폐 단위 금액은 `long`을 기본으로 하고 허용 범위를 검증합니다.
- 금액 계산에 `float`, `double`을 사용하지 않습니다.
- 비율·소수 계산이 필요하면 `BigDecimal`을 사용하고 반올림 방식과 자릿수를 명시합니다.
- 정수 합계·곱셈에서 범위 초과 가능성이 있으면 `Math.addExact`, `Math.multiplyExact` 등으로 검증합니다.
- 생성·승인·만료처럼 특정 시점을 나타내는 값은 `Instant`를 기본으로 합니다.
- 날짜만 필요한 값은 `LocalDate`, 시간 간격은 `Duration`을 사용합니다.
- 만료 판단 등 테스트에 영향을 주는 현재 시각은 `Clock`을 주입받아 얻습니다.
- API 시간 형식과 DB 시간 매핑은 명세에 맞춥니다. 이 문서만으로 기존 DB 타입을 변경하지 않습니다.

```java
Instant now = clock.instant();

if (!now.isBefore(expiresAt)) {
    throw new BusinessException(ApprovalErrorCode.APPROVAL_EXPIRED);
}
```

## 상태 변경

- 업무 객체에 상태를 임의 변경하는 public setter를 만들지 않습니다.
- `approve()`, `reject()`, `consume()`처럼 변경 의도가 드러나는 메서드를 사용합니다.
- 상태 변경 메서드 안에서 허용 조건을 확인합니다.
- 객체 상태 검사만으로 동시성을 보장한다고 가정하지 않습니다. 저장 시 상태 조건·버전·잠금 등 해당 기능의 정책을 함께 적용합니다.

## 예외 처리

- 예상되는 업무 오류는 합의된 오류 코드와 업무 예외로 표현합니다.
- `BusinessException`, `ErrorCode`, `GlobalExceptionHandler`를 공통 작성 패턴으로 추천합니다. 현재 구현되어 있다는 의미는 아닙니다.
- 여기서 공통 예외 처리는 각 서비스 내부의 HTTP 예외 처리를 뜻합니다. 공유 가능한 오류 응답 계약과 서비스별 오류 처리 구현을 구분하고, 구현을 계약 라이브러리로 옮기지 않습니다.
- 예외 타입별 별도 처리가 필요한 경우 구체적인 예외 클래스를 추가합니다.
- Controller마다 반복적인 `try-catch`를 작성하지 않고 공통 HTTP 예외 처리에 모읍니다.
- 빈 `catch`, `printStackTrace()`, 예외를 삼킨 뒤 성공 반환을 금지합니다.
- 처리할 수 있는 구체적인 예외를 잡습니다. `catch (Exception e)`는 최상위 오류 경계 등 이유가 있는 곳에서만 사용합니다.
- 다른 예외로 변환할 때 원인 예외를 보존합니다.
- 내부 예외 메시지·SQL·스택 트레이스를 클라이언트 응답에 그대로 포함하지 않습니다.

```java
try {
    return pgClient.charge(request);
} catch (PgTimeoutException exception) {
    throw new PaymentResultUnknownException(
            "PG 결제 결과를 확인하지 못했습니다.",
            exception);
}
```

위 클래스명은 작성 예시입니다. 실제 예외와 UNKNOWN 처리 정책은 해당 도메인에서 정의합니다.

## 로깅

- SLF4J 로거를 사용합니다.
- 현재 Lombok 의존성이 없으므로 로거를 명시적으로 선언합니다.
- 문자열 덧셈 대신 자리표시자를 사용합니다.
- 오류의 스택 트레이스는 책임 있는 경계에서 한 번 기록하고, 모든 계층에서 반복 기록하지 않습니다.
- 요청·응답 DTO 전체, Authorization 헤더, PIN, 카드번호, CVC, 토큰 원문, 개인키를 로그에 남기지 않습니다.
- 필요하면 요청 추적 ID·승인 ID·Payment ID 등 허용된 식별자와 처리 결과를 기록합니다.

```java
private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

log.info("Payment completed: paymentId={}, status={}", paymentId, status);
log.error("PG request failed: paymentId={}", paymentId, exception);
```

| 레벨 | 사용 기준 |
| --- | --- |
| DEBUG | 개발·진단에 필요한 상세 정보 |
| INFO | 주요 업무 처리 결과 |
| WARN | 복구 가능하지만 확인이 필요한 상황 |
| ERROR | 처리 실패 또는 운영 확인이 필요한 예상 밖 오류 |

## JDBC·SQL 작성

현재 데이터 접근 기술은 `JdbcTemplate`입니다. JPA 애너테이션·flush·fetch join 규칙은 이 문서의 전제로 두지 않습니다.

- SQL과 결과 매핑은 Repository에 모읍니다.
- 사용자 입력을 문자열로 이어 붙여 SQL을 만들지 않습니다.
- 조건값은 파라미터로 바인딩합니다.
- 동적 정렬 컬럼처럼 바인딩할 수 없는 SQL 구성요소는 허용 목록으로 제한합니다.
- 조회 컬럼을 명시하고 `SELECT *`를 기본으로 사용하지 않습니다.
- SQL 키워드는 대문자, 테이블·컬럼 이름은 기존 `snake_case`를 사용합니다.
- 여러 줄 SQL은 Java 텍스트 블록으로 작성합니다.
- 조건부 UPDATE·DELETE는 영향받은 행 수를 확인해 충돌·부재를 처리합니다.

```java
String sql = """
        SELECT id, status, expires_at
        FROM authorization_sessions
        WHERE id = ?
        """;
```

## DB 스키마·마이그레이션 작성

DB 소유권, migration 위치, 이미 적용한 파일을 수정하지 않는 규칙은 [CONTRIBUTING.md의 5. DB 변경 규칙](../CONTRIBUTING.md#5-db-변경-규칙)을 따릅니다. 아래는 이를 적용하기 위한 백엔드 보완 규칙입니다.

- 테이블·컬럼·제약·인덱스 변경은 소유 서비스의 PostgreSQL용 Flyway SQL로 작성합니다.
- DB 관리 도구에서 직접 실행한 변경만을 최종 결과로 남기지 않습니다. 동일한 구조를 migration으로 재현할 수 있어야 합니다.
- 초기 스키마는 연관된 테이블을 하나의 migration으로 작성할 수 있습니다. 이후 변경은 검토 가능한 목적 단위로 나눕니다.
- 파일명은 기존의 `V번호__변경_설명.sql` 형식을 사용합니다. 예: `V3__add_payment_status_index.sql`.
- 버전 번호는 서비스 DB별로 독립적으로 관리합니다. 서로 다른 서비스의 같은 버전 번호는 허용됩니다.
- 같은 서비스의 버전 중복과 적용 순서를 MR 전에 확인합니다. 이미 적용된 파일의 번호를 바꾸지 않고 후속 변경으로 해결합니다. 개인 로컬에만 적용한 미공유 migration을 조정해야 하면 해당 로컬 DB를 재구성해 검증합니다.
- 개발자는 개인 로컬 DB에서 변경을 검증합니다. 공용 개발 DB에는 검토·병합된 migration을 배포 절차로 적용합니다.
- 로컬·테스트 DB도 PostgreSQL을 사용해 타입·제약·잠금 동작을 확인합니다.
- 데모용 사용자·카드·주문 등 샘플 데이터는 로컬·테스트 전용으로 분리합니다. 서비스 동작에 필수인 기준 데이터는 별도로 구분해 migration에 포함할 수 있습니다.
- 빈 DB에 전체 migration을 적용하는 경우와, 직전 공유 버전 및 대표 데이터가 있는 DB에 새 migration을 적용하는 경우를 모두 검증합니다.
- 기존 데이터가 있는 컬럼에 NOT NULL·UNIQUE 등 제약을 추가할 때는 기존 데이터 처리 순서도 SQL에 반영합니다.
- DB 변경과 관련 ERD·API 계약이 달라지면 해당 문서를 같은 MR에서 갱신합니다.

JDBC는 데이터 조회·변경 코드의 방식이고 Flyway는 DB 구조 변경 이력의 관리 방식입니다. 향후 데이터 접근 기술이 달라져도 스키마 변경은 기존 Flyway 절차를 따릅니다.

## 트랜잭션 코드

- 트랜잭션은 필요한 업무 처리 메서드에 명시합니다.
- 조회 트랜잭션이 필요한 경우 `@Transactional(readOnly = true)`를 사용합니다.
- 모든 Service 클래스에 트랜잭션을 일괄 적용하지 않습니다.
- `readOnly = true`를 접근 제어나 쓰기 방지 검증의 대체로 사용하지 않습니다.
- 외부 HTTP 호출을 포함한 메서드에 무조건 긴 트랜잭션을 걸지 않습니다.
- 기본 Spring 프록시 방식에서는 같은 객체 내부 호출로 새 트랜잭션 설정이 적용된다고 가정하지 않습니다.
- 롤백이 필요한 예외를 삼키지 않고, 예외 종류에 따른 롤백 정책을 확인합니다.

## 설정값 사용

[CONTRIBUTING.md의 6. 설정과 비밀정보](../CONTRIBUTING.md#6-설정과-비밀정보)를 따릅니다.

- 서비스 주소·타임아웃 등 환경 의존 값은 코드에 고정하지 않고 설정으로 주입합니다.
- 새 설정은 `application.yml`의 환경변수 placeholder로 정의하고, 필요한 변수명과 용도를 문서에 적습니다.
- 실제 비밀값은 코드·문서·테스트 예제에 넣지 않습니다. 로컬 `.env`와 CI 변수의 관리 방식은 기존 가이드를 따릅니다.
- 예제에 `local`·`prod` 프로필이나 특정 보안·문서화 라이브러리가 이미 존재한다고 가정하지 않습니다. 실제 설정과 의존성을 먼저 확인합니다.
- Redis 관련 코드는 캐시·잠금·세션 등 사용 목적이 확정된 경우에만 추가합니다.

## 주석·Javadoc

- 주석은 코드만으로 드러나지 않는 이유, 제약, 외부 계약을 설명합니다.
- 변경된 코드와 맞지 않는 주석을 남기지 않습니다.
- 사용하지 않는 코드를 주석 처리해 보관하지 않습니다.
- 모든 getter·생성자에 의미 없는 Javadoc을 추가하지 않습니다.
- 공유 계약, 비직관적 반환값, 호출자가 알아야 할 예외·부작용에는 Javadoc을 작성합니다.
- TODO에는 후속 작업 내용과 추적 가능한 Jira 이슈 키를 적습니다.

```java
// PG 응답 유실 가능성이 있어 새 결제 요청을 만들지 않고 기존 거래를 조회한다.
```

## 테스트 코드

- 테스트 클래스는 대상 코드와 같은 모듈·패키지의 `src/test/java`에 작성합니다.
- 단위 테스트는 `대상Test`, DB·외부 연동 통합 테스트는 `대상IntegrationTest`로 작성합니다.
- 테스트 메서드는 `동작_조건_기대결과` 형태로 작성합니다.
- JUnit `@DisplayName`으로 한국어 설명을 추가할 수 있습니다.
- Given·When·Then 순서를 유지하고, 예외 검증처럼 결합이 자연스러운 경우 억지로 분리하지 않습니다.
- 테스트 실행 순서나 다른 테스트의 데이터에 의존하지 않습니다.
- 시간 경계 테스트는 고정 Clock을 사용합니다.
- 동시성 테스트는 임의의 sleep보다 시작·완료 동기화 수단을 사용합니다.
- 업무 결과와 부작용을 검증합니다. 클래스 존재 확인만으로 기능 테스트를 대체하지 않습니다.
- 테스트 fixture를 운영 코드에서 사용하지 않습니다.
- fixture는 해당 모듈에서 먼저 작성하고, 실제로 여러 모듈에서 재사용할 때만 `test-support`로 옮깁니다.
- 실제 PG·Merchant 대신 기존 Mock 서비스를 사용합니다. 업무 규칙 단위 테스트에서는 테스트 대역으로 외부 호출을 격리합니다.
- 실제 인증정보·실제 고객정보를 테스트 데이터에 사용하지 않습니다.

```java
@Test
@DisplayName("만료된 승인 요청은 승인할 수 없다")
void approve_expiredApproval_throwsException() {
    // given
    Approval approval = expiredApproval();

    // when & then
    assertThrows(ApprovalExpiredException.class, () -> approval.approve(now));
}
```

예제의 fixture와 객체는 설명용입니다. 실제 프로젝트의 승인 메서드 시그니처에 맞춰 작성합니다.

## 자동 검사와 검증

현재 자동 검사에 포함된 항목:

- UTF-8, 탭 금지, 파일 끝 개행
- 최대 120자 제한과 설정된 예외
- 와일드카드 import 금지
- 미사용·중복 import 검사
- 중괄호, 빈 문장, 한 줄 한 문장 등 기존 Checkstyle 규칙

들여쓰기·네이밍·import 정렬·업무 작성 규칙이 전부 Checkstyle로 검사되는 것은 아닙니다. 팀 합의 후 검사 가능한 규칙은 설정에 반영합니다.

Gradle은 설치본 대신 저장소의 Wrapper를 사용합니다. 컨테이너 변경 시 이미지 빌드 확인, MR 전 체크리스트, 브랜치·리뷰·배포 절차는 CONTRIBUTING.md의 7~12항을 그대로 따릅니다.

`backend/`에서 실행합니다. Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용합니다.

```bash
# 작업 중에는 변경한 모듈을 확인한다.
./gradlew :apps:pay-service:build

# MR 전 전체 검증은 기존 팀 개발 가이드를 따른다.
./gradlew checkstyleMain checkstyleTest build
```

이 문서의 클래스와 코드 예제는 작성 형태를 설명합니다. 실제 구현·업무 정책·새 라이브러리 도입 여부가 확정되었다는 의미는 아닙니다.
