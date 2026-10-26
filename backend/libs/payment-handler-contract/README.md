# Payment Handler Contract

결제 handler 경계의 계약, DTO, schema, validation 지원 코드를 공유하는 순수 Java 라이브러리입니다.

권한 부여, mandate, credential, 결제 요청·응답의 데이터 모양을 정의할 수 있습니다. Controller, 결제 처리 구현, JPA Entity, Repository, 비즈니스 규칙은 넣지 않습니다.

계약이 바뀌면 `docs/payment-handler/`를 같은 MR에서 갱신하고 제공자·소비자 테스트를 함께 수정합니다.
