package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code AddressControllerTest}와 같다. */
@WebMvcTest(
        controllers = AgentConnectionController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class AgentConnectionControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentConnectionService connectionService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("연동 목록을 반환한다")
    void list_returnsConnections() throws Exception {
        stubUser();
        UUID connectionId = UUID.randomUUID();
        Instant connectedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(connectionService.list(USER_ID)).thenReturn(List.of(
                new AgentConnectionSummary(connectionId, "ChatGPT", null, "[\"catalog:read\"]", connectedAt, null)));
        when(connectionService.readScopes("[\"catalog:read\"]")).thenReturn(List.of("catalog:read"));

        mockMvc.perform(get("/api/core/v1/users/me/agent-connections").with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].connectionId").value(connectionId.toString()))
                .andExpect(jsonPath("$[0].clientName").value("ChatGPT"))
                .andExpect(jsonPath("$[0].scopes[0]").value("catalog:read"));
    }

    @Test
    @DisplayName("연동 해제는 204를 반환한다")
    void revoke_returns204() throws Exception {
        stubUser();
        UUID connectionId = UUID.randomUUID();

        mockMvc.perform(delete("/api/core/v1/users/me/agent-connections/" + connectionId)
                        .with(this::withPrincipal))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 연동을 해제하면 404 AGENT_CONNECTION_NOT_FOUND를 반환한다")
    void revoke_notFound_returns404() throws Exception {
        stubUser();
        UUID connectionId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new CoreException(CoreErrorCode.AGENT_CONNECTION_NOT_FOUND))
                .when(connectionService).revoke(USER_ID, connectionId);

        mockMvc.perform(delete("/api/core/v1/users/me/agent-connections/" + connectionId)
                        .with(this::withPrincipal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("AGENT_CONNECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("필터를 거치지 않아 principal이 없으면 401 AUTH_TOKEN_MISSING을 반환한다")
    void list_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/users/me/agent-connections"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    private void stubUser() {
        User user = new User(
                USER_ID, PRINCIPAL, "tester", UserStatus.ACTIVE, null, null, false, null, null,
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByPublicId(PRINCIPAL)).thenReturn(Optional.of(user));
    }

    private MockHttpServletRequest withPrincipal(MockHttpServletRequest request) {
        CoreRequestContext.setPrincipal(request, PRINCIPAL);
        return request;
    }
}
