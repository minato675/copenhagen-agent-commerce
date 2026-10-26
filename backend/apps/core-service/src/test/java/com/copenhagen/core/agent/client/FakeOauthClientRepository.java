package com.copenhagen.core.agent.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FakeOauthClientRepository implements OauthClientRepository {

    private final List<OauthClient> clients = new ArrayList<>();

    public void add(OauthClient client) {
        clients.add(client);
    }

    @Override
    public Optional<OauthClient> findByClientId(String clientId) {
        return clients.stream().filter(client -> client.clientId().equals(clientId)).findFirst();
    }

    @Override
    public Optional<OauthClient> findById(long id) {
        return clients.stream().filter(client -> client.id() == id).findFirst();
    }
}
