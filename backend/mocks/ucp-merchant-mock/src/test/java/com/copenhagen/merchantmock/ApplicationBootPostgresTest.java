package com.copenhagen.merchantmock;

import java.util.Map;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import com.copenhagen.merchantmock.support.RequestIdFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 앱이 실제로 뜬다. 진짜 PostgreSQL 위에서 Flyway 까지 돌고 서블릿 필터와 logback 설정이 읽힌 상태다.
 *
 * <p>2026-09-17 187 의 MR 스모크가 떨어졠을 때 이 모듈에 컨텍스트를 띄우는 테스트가 하나도 없어 로컬에서
 * 원인을 못 잡았다. 유닛 테스트 274개가 전부 초록이어도 {@code logback-spring.xml} 한 줄이나 Bean 배선 하나가
 * 틀리면 컨테이너는 헬스체크에서 죽는다. 그 구멍을 여기서 막는다(DESIGN.md 25.5절의 1단계와 같은 검사).
 */
@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationBootPostgresTest {

    private static EmbeddedPostgresSupport postgres;

    @LocalServerPort
    private int port;

    @Autowired
    private RequestIdFilter filter;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.jdbcUrl("public"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Test
    @DisplayName("컨텍스트가 뜨고 헬스체크가 UP 이다. compose 헬스체크와 스모크 1단계가 보는 것")
    void context_starts_andHealthIsUp() {
        ResponseEntity<Map> response = RestClient.create()
                .get().uri("http://localhost:" + port + "/actuator/health")
                .retrieve().toEntity(Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertNotNull(filter, "요청 컨텍스트 필터가 Bean 으로 떠 있어야 한다");
    }

    @Test
    @DisplayName("Profile 이 나오고 keys 가 비어 있지 않다. 스모크가 Core 컨테이너에서 확인하는 조건")
    void profile_hasRestTransportAndKeys() {
        ResponseEntity<Map> response = RestClient.create()
                .get().uri("http://localhost:" + port + "/.well-known/ucp")
                .retrieve().toEntity(Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().containsKey("keys"));
        assertTrue(((java.util.List<?>) response.getBody().get("keys")).size() > 0);
    }

    @Test
    @DisplayName("X-Request-Id 를 보내면 응답 헤더로 되돌아온다. 필터가 실제 서블릿 체인에 걸려 있다")
    void requestId_isEchoed() {
        ResponseEntity<String> response = RestClient.create()
                .get().uri("http://localhost:" + port + "/internal/v1/scenarios")
                .header(RequestIdFilter.HEADER, "boot-test-1")
                .retrieve().toEntity(String.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("boot-test-1", response.getHeaders().getFirst(RequestIdFilter.HEADER));
    }
}
