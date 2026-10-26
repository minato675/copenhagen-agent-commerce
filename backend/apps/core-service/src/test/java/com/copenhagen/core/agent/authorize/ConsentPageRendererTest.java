package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentPageRendererTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000422");

    @Test
    @DisplayName("요청된 권한만 설명하고 기존 로그인 필드와 승인·거부 POST 계약을 유지한다")
    void consent_preservesFormContractAndRequestedScopes() {
        String html = ConsentPageRenderer.renderConsent(REQUEST_ID, "ChatGPT", null, List.of("catalog:read"), null);

        assertThat(html).contains("method=\"post\" action=\"/oauth/authorize/" + REQUEST_ID + "/decision\"",
                "name=\"loginId\" required autocomplete=\"username\"",
                "type=\"password\" name=\"password\" required autocomplete=\"current-password\"",
                "name=\"decision\" value=\"APPROVE\"", "value=\"DENY\" formnovalidate",
                "상품 검색 및 가격·옵션 조회");
        assertThat(html).doesNotContain("내 장바구니 조회", "장바구니 상품 추가", "앱에서 구매를 이어가기",
                "client-logo", "role=\"alert\"");
    }

    @Test
    @DisplayName("클라이언트 이름·로고 속성·알 수 없는 권한·오류를 HTML로 실행하지 않는다")
    void consent_escapesEveryDynamicValue() {
        String html = ConsentPageRenderer.renderConsent(REQUEST_ID, "<script>client</script>",
                "https://example.com/logo.png\" onerror=\"alert('logo')",
                List.of("<img src=x onerror=alert(1)>"), "<script>error</script> & \"실패\"");

        assertThat(html).contains("&lt;script&gt;client&lt;/script&gt;",
                "logo.png&quot; onerror=&quot;alert(&#39;logo&#39;)",
                "&lt;img src=x onerror=alert(1)&gt;",
                "&lt;script&gt;error&lt;/script&gt; &amp; &quot;실패&quot;");
        assertThat(html).doesNotContain("<script>", "<img src=x", " onerror=\"");
    }

    @Test
    @DisplayName("로그인 실패 안내를 폼에 연결하고 입력값이나 비밀번호를 다시 렌더링하지 않는다")
    void loginFailure_hasAccessibleErrorAndEmptyFields() {
        String html = ConsentPageRenderer.renderConsent(REQUEST_ID, "ChatGPT", "", List.of("cart:read"),
                "아이디 또는 비밀번호가 올바르지 않습니다.");

        assertThat(html).contains("id=\"login-error\" role=\"alert\"", "aria-describedby=\"login-error\"",
                "아이디 또는 비밀번호가 올바르지 않습니다.", "내 장바구니 조회");
        assertThat(html).doesNotContain("name=\"loginId\" value=", "name=\"password\" value=", "client-logo");
    }

    @Test
    @DisplayName("장바구니 변경·구매 인계 권한을 실제 기능 범위로 안내한다")
    void consent_describesWriteAndHandoffScopes() {
        String html = ConsentPageRenderer.renderConsent(REQUEST_ID, "ChatGPT", null,
                List.of("cart:write", "purchase:handoff", "future:scope"), null);

        assertThat(html).contains("장바구니 상품 추가·변경·삭제", "future:scope",
                "앱에서 구매를 이어가기 위한 요청 및 최근 6개월 구매내역 조회");
        assertThat(html).doesNotContain("상품 검색 및 가격·옵션 조회", "내 장바구니 조회");
    }

    @Test
    @DisplayName("연동 불가 화면은 오류를 이스케이프하고 임의 URL로 이동하거나 폼을 제출하지 않는다")
    void error_escapesMessageWithoutRetryingExpiredRequest() {
        String html = ConsentPageRenderer.renderError("만료 <script>alert(1)</script>");

        assertThat(html).contains("role=\"alert\"", "만료 &lt;script&gt;alert(1)&lt;/script&gt;",
                "계정 연결을 다시 시도해 주세요.", "/oauth/authorize/assets/v1/consent.css");
        assertThat(html).doesNotContain("<script>", "<form", "javascript:");
    }
}
