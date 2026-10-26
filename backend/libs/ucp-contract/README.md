# UCP Contract

UCP 경계의 계약, DTO, schema, validation 지원 코드를 공유하는 순수 Java 라이브러리입니다.

넣을 수 있는 범위는 외부 UCP 요청·응답 형식과 해당 계약을 표현하는 코드뿐입니다. Controller, HTTP client, JPA Entity, Repository, 도메인 규칙과 애플리케이션 구현은 넣지 않습니다.

계약이 바뀌면 `docs/ucp/`를 같은 MR에서 갱신하고 관련 제공자·소비자 테스트를 함께 수정합니다.
