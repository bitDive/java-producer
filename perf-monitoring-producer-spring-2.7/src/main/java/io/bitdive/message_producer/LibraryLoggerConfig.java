package io.bitdive.message_producer;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
public class LibraryLoggerConfig {
    private static final LoggerContext loggerContext;

    static {
        try {
            URI configUri = Objects.requireNonNull(LibraryLoggerConfig.class.getResource("/monitoringCustomConfig.xml")).toURI();
            loggerContext = new LoggerContext("MonitoringCustomConfig", null, configUri);
            loggerContext.start();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }


    public static Logger getLogger(Class<?> clazz) {
        return loggerContext.getLogger(clazz.getName());
    }
}
