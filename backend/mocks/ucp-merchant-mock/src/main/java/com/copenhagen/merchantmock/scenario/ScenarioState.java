package com.copenhagen.merchantmock.scenario;

import java.util.concurrent.atomic.AtomicReference;

import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 현재 시나리오. DB 가 아니라 메모리에 둔다. 재기동하면 설정의 initial 로 돌아가는 것이 안전하다(DESIGN.md 9절).
 *
 * <p>바꾸는 것은 {@link ScenarioController}(185) 하나다. 체크아웃 판정과 웹훅 발송기는 읽기만 한다(9.3절).
 */
@Component
public class ScenarioState {

    private final AtomicReference<Scenario> current;

    /** 생성자가 둘이라 Spring 이 고를 수 있게 표시한다. 다른 하나는 테스트용이다. */
    @Autowired
    public ScenarioState(MerchantProperties properties) {
        this(Scenario.valueOf(properties.scenario().initial()));
    }

    public ScenarioState(Scenario initial) {
        this.current = new AtomicReference<>(initial);
    }

    public Scenario current() {
        return current.get();
    }

    public boolean is(Scenario scenario) {
        return current.get() == scenario;
    }

    public void set(Scenario scenario) {
        current.set(scenario);
    }
}
