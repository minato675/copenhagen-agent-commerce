package com.copenhagen.merchantmock.support;

import java.time.Duration;

/**
 * 기다리는 방법. 시나리오 지연이 있는 곳(웹훅 발송기, Complete)이 같이 쓴다(DESIGN.md 9.3절).
 *
 * <p>테스트가 실제로 잠들지 않고 기다리라고 한 시간만 기록할 수 있게 뗐다. 운영은 {@link #THREAD} 다.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = Thread::sleep;

    void sleep(Duration duration) throws InterruptedException;
}
