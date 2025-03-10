package io.bitdive.parent.trasirovka.agent.utils;

public enum MessageTypeEnum {
    STAR,
    END,
    SQL_START,
    SQL_END,
    WEB_RESPONSE,
    WEB_REQUEST,
    CRITICAL_DB_ERROR,
    CRITICAL_KAFKA_ERROR,
    KAFKA_SEND,
    KAFKA_CONSUMER,
}
