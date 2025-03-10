package io.bitdive.parent.trasirovka.agent.utils;


import java.util.Optional;

public class KafkaAgentStorage {
    public static String KAFKA_BOOTSTRAP_PRODUCER_STRING;
    public static String KAFKA_BOOTSTRAP_CONSUMER_STRING;

    public static String getBootstrap() {
        return Optional.ofNullable(KAFKA_BOOTSTRAP_PRODUCER_STRING)
                .orElse(KAFKA_BOOTSTRAP_CONSUMER_STRING);
    }
}
