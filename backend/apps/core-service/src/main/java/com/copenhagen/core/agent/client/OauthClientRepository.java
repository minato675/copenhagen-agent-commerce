package com.copenhagen.core.agent.client;

import java.util.Optional;

public interface OauthClientRepository {

    Optional<OauthClient> findByClientId(String clientId);

    Optional<OauthClient> findById(long id);
}
