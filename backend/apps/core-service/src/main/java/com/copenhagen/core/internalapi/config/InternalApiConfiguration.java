package com.copenhagen.core.internalapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.agent.client.OauthClientRepository;
import com.copenhagen.core.agent.connection.AgentConnectionRepository;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.internalapi.auth.InternalApiAuthenticationFilter;
import com.copenhagen.core.internalapi.auth.InternalApiTokenVerifier;
import com.copenhagen.core.internalapi.auth.InternalScopes;
import com.copenhagen.core.internalapi.error.InternalApiErrorWriter;

/**
 * 내부 API 필터 등록. {@code @Component}로 두면 모든 {@code @WebMvcTest} 슬라이스가 이 필터를 함께
 * 만들려다 실패하므로(필요한 빈이 슬라이스에 없다) 여기서 명시적으로 등록한다.
 *
 * <p>앱용 {@code CoreAuthenticationFilter}(HIGHEST_PRECEDENCE + 10)보다 뒤에 두지만, 두 필터는
 * 경로가 겹치지 않아 순서가 동작에 영향을 주지는 않는다.
 */
@Configuration
public class InternalApiConfiguration {

    static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 11;

    @Bean
    public FilterRegistrationBean<InternalApiAuthenticationFilter> internalApiAuthenticationFilter(
            InternalApiTokenVerifier tokenVerifier,
            OauthClientRepository clientRepository,
            UserRepository userRepository,
            AgentConnectionRepository connectionRepository,
            InternalApiErrorWriter errorWriter,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalApiAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new InternalApiAuthenticationFilter(
                        tokenVerifier, clientRepository, userRepository, connectionRepository,
                        errorWriter, objectMapper));
        registration.addUrlPatterns(InternalScopes.PREFIX + "*");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
