# Internal API Contract

우리 서비스 사이의 내부 HTTP 계약과 전송 DTO를 공유하는 순수 Java 라이브러리입니다.

넣을 수 있는 것:

- 내부 API request/response DTO
- endpoint와 오류 형식 계약
- 계약에서 사용하는 enum, schema, validation 지원 코드

넣으면 안 되는 것:

- Controller와 HTTP client 구현
- JPA Entity, Repository, Domain/Application Service
- 특정 애플리케이션의 비즈니스 규칙

계약 변경 시 제공자와 소비자 테스트를 함께 수정하고, 호환성이 깨지면 `docs/api/`와 ADR에 전환 방법을 기록합니다.
