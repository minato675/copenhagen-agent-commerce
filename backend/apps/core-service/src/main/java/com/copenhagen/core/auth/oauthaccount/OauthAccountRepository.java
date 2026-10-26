package com.copenhagen.core.auth.oauthaccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OauthAccountRepository {

    long insert(long userId, OauthProvider provider, String providerSubject, Instant now);

    Optional<OauthAccount> findByProviderAndProviderSubject(OauthProvider provider, String providerSubject);

    List<OauthAccount> findAllByUserId(long userId);
}
