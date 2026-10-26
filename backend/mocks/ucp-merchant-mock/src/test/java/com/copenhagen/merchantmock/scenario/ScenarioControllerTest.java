package com.copenhagen.merchantmock.scenario;

import com.copenhagen.merchantmock.support.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시나리오 스위치의 HTTP 계약(DESIGN.md 9.2절). 앱을 켠 채로 바꾸고, 모르는 이름은 네 필드 Envelope 으로 거절한다.
 */
class ScenarioControllerTest {

    private static final String PATH = "/internal/v1/scenarios";

    private ScenarioState state;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        state = new ScenarioState(Scenario.NORMAL);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScenarioController(state))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET 은 지금 켜진 시나리오다. 기동 직후는 initial 값")
    void get_afterStartup_returnsInitial() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("NORMAL"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Scenario.class)
    @DisplayName("POST 는 10종 모두 받고, 바뀐 값을 돌려주며, 상태가 실제로 바뀐다")
    void post_everyScenario_switchesState(Scenario scenario) throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"" + scenario.name() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value(scenario.name()));

        assertEquals(scenario, state.current());
    }

    @Test
    @DisplayName("NORMAL 을 넣으면 즉시 정상으로 돌아온다")
    void post_normal_resets() throws Exception {
        state.set(Scenario.PRICE_CHANGED);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"NORMAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("NORMAL"));

        assertEquals(Scenario.NORMAL, state.current());
    }

    @Test
    @DisplayName("모르는 이름은 400 INVALID_REQUEST 이고 네 필드 Envelope 이다. 상태는 그대로다")
    void post_unknownName_returns400WithEnvelope() throws Exception {
        state.set(Scenario.OUT_OF_STOCK);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"FOO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("모르는 시나리오: FOO"))
                .andExpect(jsonPath("$.currentState").value((Object) null))
                .andExpect(jsonPath("$.nextAction").value((Object) null));

        assertEquals(Scenario.OUT_OF_STOCK, state.current(), "거절된 요청은 상태를 건드리지 않는다");
    }

    @Test
    @DisplayName("이름이 비어 있어도 400 INVALID_REQUEST")
    void post_blankName_returns400() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("소문자는 받지 않는다. 이름은 열 개 그대로다")
    void post_lowercase_returns400() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"price_changed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
}
