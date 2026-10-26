package com.copenhagen.core.agent.authorize;

import java.util.List;
import java.util.UUID;

/** Core가 직접 제공하는 OAuth 로그인·동의 화면. 동적 값은 이스케이프하고 스타일은 정적 리소스로 분리한다. */
final class ConsentPageRenderer {

    private static final String ASSETS = "/oauth/authorize/assets/v1/";

    private ConsentPageRenderer() {
    }

    static String renderConsent(
            UUID requestId, String clientName, String logoUrl, List<String> scopes, String errorMessage) {
        StringBuilder html = pageStart("코펜하겐 계정 연결");
        html.append("""
                <p class="eyebrow">계정 연결</p>
                <h1>대화에서 쇼핑을 시작하세요.</h1>
                <p class="intro">코펜하겐 계정으로 로그인하고,<br class="desktop-break">
                아래 서비스에서 쇼핑을 이어가세요.</p>
                <div class="client">
                """);
        if (logoUrl != null && !logoUrl.isBlank()) {
            html.append("<img class=\"client-logo\" src=\"").append(escape(logoUrl))
                    .append("\" alt=\"\" width=\"40\" height=\"40\" referrerpolicy=\"no-referrer\">");
        }
        html.append("<div><span class=\"caption\">연결할 서비스</span><strong>")
                .append(escape(clientName)).append("</strong></div><span class=\"link-icon\" aria-hidden=\"true\">")
                .append("↗</span></div>");
        boolean hasError = errorMessage != null && !errorMessage.isBlank();
        if (hasError) {
            html.append("<div class=\"error-notice\" id=\"login-error\" role=\"alert\">")
                    .append("<strong>로그인 정보를 확인해 주세요.</strong><p>")
                    .append(escape(errorMessage)).append("</p></div>");
        }
        html.append("<form method=\"post\" action=\"/oauth/authorize/").append(requestId).append("/decision\"");
        if (hasError) {
            html.append(" aria-describedby=\"login-error\"");
        }
        html.append("""
                >
                <div class="field">
                  <label for="login-id">아이디</label>
                  <input id="login-id" type="text" name="loginId" required autocomplete="username"
                    autocapitalize="none" spellcheck="false" placeholder="앱에서 사용하는 아이디">
                </div>
                <div class="field">
                  <label for="password">비밀번호</label>
                  <input id="password" type="password" name="password" required autocomplete="current-password"
                    placeholder="비밀번호 입력">
                </div>
                <section class="permissions" aria-labelledby="permissions-title">
                  <h2 id="permissions-title">연결 시 허용할 권한</h2>
                  <ul>
                """);
        for (String scope : scopes) {
            html.append("<li><span class=\"permission-dot\" aria-hidden=\"true\"></span><span>")
                    .append(escape(scopeLabel(scope))).append("</span></li>");
        }
        html.append("""
                  </ul>
                </section>
                <p class="consent-note">아래 버튼을 누르면 위 권한으로 계정 연결에 동의합니다.</p>
                <div class="actions">
                  <button class="primary" type="submit" name="decision" value="APPROVE">
                    로그인하고 연결<span aria-hidden="true">→</span>
                  </button>
                  <button class="secondary" type="submit" name="decision" value="DENY" formnovalidate>
                    연결하지 않기
                  </button>
                </div>
                </form>
                <p class="help">연결된 서비스는 코펜하겐 앱에서 관리할 수 있어요.</p>
                """);
        return pageEnd(html);
    }

    static String renderError(String message) {
        StringBuilder html = pageStart("코펜하겐 계정 연결 오류");
        html.append("""
                <p class="eyebrow">계정 연결</p>
                <div class="error-mark" aria-hidden="true">!</div>
                <h1>연동을 진행할 수<br>없습니다.</h1>
                <div class="error-notice" role="alert"><p>
                """).append(escape(message)).append("""
                </p></div>
                <p class="intro">이 창을 닫고 연결을 시작한 서비스로 돌아가<br class="desktop-break">
                계정 연결을 다시 시도해 주세요.</p>
                """);
        return pageEnd(html);
    }

    private static StringBuilder pageStart(String title) {
        return new StringBuilder("""
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <meta name="color-scheme" content="light dark">
                  <meta name="referrer" content="no-referrer">
                """).append("<title>").append(escape(title)).append("</title>")
                .append("<link rel=\"stylesheet\" href=\"").append(ASSETS).append("consent.css?v=20260922\">")
                .append("</head><body><div class=\"page\"><header class=\"brand\"><img src=\"")
                .append(ASSETS).append("copenhagen-symbol.png\" alt=\"COPENHAGEN\" width=\"64\">")
                .append("</header><main id=\"main\" class=\"panel\">");
    }

    private static String pageEnd(StringBuilder html) {
        return html.append("</main><footer>찾고, 비교하고. 코펜하겐</footer></div></body></html>").toString();
    }

    private static String scopeLabel(String scope) {
        return switch (scope) {
            case "catalog:read" -> "상품 검색 및 가격·옵션 조회";
            case "cart:read" -> "내 장바구니 조회";
            case "cart:write" -> "장바구니 상품 추가·변경·삭제";
            case "purchase:handoff" -> "앱에서 구매를 이어가기 위한 요청 및 최근 6개월 구매내역 조회";
            default -> scope;
        };
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
