package com.copenhagen.merchantmock.scenario;

import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.Audit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연용 고장 스위치를 앱을 켠 채로 돌리는 내부 API(DESIGN.md 9.2절).
 *
 * <p>{@code POST} 는 바꾸고 바뀐 값을 돌려준다. {@code NORMAL} 을 넣으면 초기화다. {@code GET} 은 지금 값이다.
 * 전역 하나이고 체크아웃 단위는 없다. 인증도 없다 — Nginx 가 {@code /internal/} 을 404 로 막고 Docker
 * 내부망에서만 닿는다(3.3절). 팀 API 명세에는 올리지 않는다. 서비스 간 계약이 아니라 시연자가 치는 스위치다.
 */
@RestController
@RequestMapping(path = "/internal/v1/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScenarioController {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioController.class);

    private final ScenarioState state;

    public ScenarioController(ScenarioState state) {
        this.state = state;
    }

    @GetMapping
    public ScenarioView current() {
        return new ScenarioView(state.current());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioView set(@RequestBody ScenarioRequest request) {
        Scenario next = parse(request);
        Scenario previous = state.current();
        state.set(next);
        LOG.info("시나리오 변경. {} → {}", previous, next);
        Audit.event("scenario.changed").before(previous).after(next).log();
        return new ScenarioView(next);
    }

    /** 비었거나 10종 밖이면 400. 요청 형식 문제라 10절 규칙대로 {@code INVALID_REQUEST} 다. */
    private static Scenario parse(ScenarioRequest request) {
        String name = request == null || request.scenario() == null ? "" : request.scenario().trim();
        if (name.isEmpty()) {
            throw ApiException.invalidRequest("scenario 는 필수다");
        }
        try {
            return Scenario.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("모르는 시나리오: " + name);
        }
    }
}
