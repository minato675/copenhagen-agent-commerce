package com.copenhagen.core.agent.discovery;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.copenhagen.core.agent.AgentScopes;
import com.copenhagen.core.agent.jwt.AgentOauthProperties;

/** RFC 8414 OAuth Authorization Server Metadata. mcp-server 이슈400 배포 문서가 전제하는 공개 경로. */
@RestController
public class OauthDiscoveryController {

    private final AgentOauthProperties properties;

    public OauthDiscoveryController(AgentOauthProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public Metadata metadata() {
        return new Metadata(
                properties.issuer(),
                true,
                properties.authorizationEndpoint(),
                properties.tokenEndpoint(),
                properties.jwksUri(),
                List.of("code"),
                List.of("authorization_code", "refresh_token", "client_credentials"),
                List.of("none", "private_key_jwt"),
                List.of("S256"),
                List.copyOf(AgentScopes.ALL));
    }

    /** RFC 8414 필드명(snake_case)을 그대로 노출한다. */
    public record Metadata(
            String issuer,
            @JsonProperty("authorization_response_iss_parameter_supported")
            boolean authorizationResponseIssParameterSupported,
            @JsonProperty("authorization_endpoint") String authorizationEndpoint,
            @JsonProperty("token_endpoint") String tokenEndpoint,
            @JsonProperty("jwks_uri") String jwksUri,
            @JsonProperty("response_types_supported") List<String> responseTypesSupported,
            @JsonProperty("grant_types_supported") List<String> grantTypesSupported,
            @JsonProperty("token_endpoint_auth_methods_supported") List<String> tokenEndpointAuthMethodsSupported,
            @JsonProperty("code_challenge_methods_supported") List<String> codeChallengeMethodsSupported,
            @JsonProperty("scopes_supported") List<String> scopesSupported) {
    }
}
