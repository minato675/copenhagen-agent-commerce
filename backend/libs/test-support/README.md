# Test Support

둘 이상의 Backend 모듈에서 재사용하는 테스트 fixture와 테스트 전용 지원 코드를 두는 순수 Java 라이브러리입니다.

- 먼저 각 모듈의 `src/test/java`에 테스트 코드를 둡니다.
- 실제로 여러 모듈에서 같은 fixture가 필요할 때만 이곳으로 옮깁니다.
- 운영 코드, 비즈니스 규칙, 애플리케이션 구현은 넣지 않습니다.
- 이 모듈의 코드는 production runtime dependency로 사용하지 않습니다.
