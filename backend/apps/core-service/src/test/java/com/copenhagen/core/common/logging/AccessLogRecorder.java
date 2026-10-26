package com.copenhagen.core.common.logging;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * {@code access} 로거가 실제로 내보낸 줄을 테스트에서 읽기 위한 도구.
 *
 * <p>{@code try (AccessLogRecorder recorder = AccessLogRecorder.attach())} 로 쓴다. 닫으면 appender를
 * 떼어 내므로 다음 테스트에 줄이 남지 않는다.
 */
final class AccessLogRecorder implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private AccessLogRecorder(Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    static AccessLogRecorder attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogLine.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new AccessLogRecorder(logger, appender);
    }

    List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    String singleLine() {
        List<String> lines = lines();
        if (lines.size() != 1) {
            throw new IllegalStateException("한 줄을 기대했는데 " + lines.size() + "줄이다: " + lines);
        }
        return lines.get(0);
    }

    @Override
    public void close() {
        appender.stop();
        logger.detachAppender(appender);
    }
}
