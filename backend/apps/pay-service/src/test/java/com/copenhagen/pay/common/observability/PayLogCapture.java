package com.copenhagen.pay.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

/** 테스트 중 발생한 Pay 로그를 수집해 필드와 민감정보를 검증합니다. */
public final class PayLogCapture implements AutoCloseable {

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public PayLogCapture() {
        appender.start();
        rootLogger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> messages() {
        return events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    public String allText() {
        return events().stream()
                .map(this::eventText)
                .collect(Collectors.joining("\n"));
    }

    public long count(String fragment) {
        return messages().stream()
                .filter(message -> message.contains(fragment))
                .count();
    }

    private String eventText(ILoggingEvent event) {
        String throwable = event.getThrowableProxy() == null
                ? ""
                : ThrowableProxyUtil.asString(event.getThrowableProxy());
        return event.getFormattedMessage() + " " + event.getMDCPropertyMap() + throwable;
    }

    @Override
    public void close() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }
}
