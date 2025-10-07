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

    STOMP_SEND,
    STOMP_CONSUMER,

    RAW_WS_CONSUMER,

    CASSANDRA_DB_START,
    CASSANDRA_DB_END,

    MONGO_DB_START,
    MONGO_DB_END,

    REDIS_DB_START,
    REDIS_DB_END,

    NEO4J_DB_START,
    NEO4J_DB_END,

    SOAP_START,
    SOAP_END,
}
