package com.copenhagen.core.agent.token;

import java.time.Instant;

public interface OauthClientAssertionJtiRepository {

    /** {@code (oauth_client_id, jti)}가 이미 있으면 삽입하지 않고 false를 반환한다(재사용 거부). */
    boolean tryConsume(long oauthClientId, String jti, Instant expiresAt);
}
