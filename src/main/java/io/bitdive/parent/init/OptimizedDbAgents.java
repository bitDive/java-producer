package io.bitdive.parent.init;

import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Умная обертка для агентов БД с автоматической проверкой наличия драйверов.
 * АВТОМАТИЧЕСКИ определяет, какие БД доступны в classpath и инициализирует
 * только нужные агенты.
 * Никаких ручных настроек не требуется!
 */
public class OptimizedDbAgents {

    /**
     * Проверяет, доступен ли класс в classpath.
     */
    private static boolean isDriverAvailable(String className) {
        try {
            Class.forName(className, false, OptimizedDbAgents.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Инициализирует агент Cassandra только если драйвер Cassandra присутствует в
     * classpath.
     * Автоматическая оптимизация - не требует настройки!
     */
    public static AgentBuilder initCassandra(AgentBuilder builder) {
        if (!isDriverAvailable("com.datastax.oss.driver.api.core.CqlSession")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Cassandra driver not found in classpath, skipping agent initialization");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Cassandra driver detected, initializing agent");
        }
        return ByteBuddyAgentCassandra.init(builder);
    }

    /**
     * Инициализирует агент MongoDB только если драйвер MongoDB присутствует в
     * classpath.
     */
    public static AgentBuilder initMongo(AgentBuilder builder) {
        if (!isDriverAvailable("com.mongodb.client.MongoClient")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ MongoDB driver not found in classpath, skipping agent initialization");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ MongoDB driver detected, initializing agent");
        }
        return ByteBuddyAgentMongoDelegate.init(builder);
    }

    /**
     * Инициализирует агент Redis только если драйвер Redis (Jedis) присутствует в
     * classpath.
     */
    public static AgentBuilder initRedis(AgentBuilder builder) {
        if (!isDriverAvailable("redis.clients.jedis.Jedis")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Redis driver (Jedis) not found in classpath, skipping agent initialization");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Redis driver (Jedis) detected, initializing agent");
        }
        return ByteBuddyAgentRedis.init(builder);
    }

    /**
     * Инициализирует агент Neo4j только если драйвер Neo4j присутствует в
     * classpath.
     */
    public static AgentBuilder initNeo4j(AgentBuilder builder) {
        if (!isDriverAvailable("org.neo4j.driver.Driver")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Neo4j driver not found in classpath, skipping agent initialization");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Neo4j driver detected, initializing agent");
        }
        return ByteBuddyAgentNeo4j.init(builder);
    }

    /**
     * Инициализирует агенты OpenSearch только если клиент OpenSearch присутствует в
     * classpath.
     */
    public static AgentBuilder initOpenSearch(AgentBuilder builder) {
        if (!isDriverAvailable("org.opensearch.client.RestClient")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ OpenSearch client not found in classpath, skipping agent initialization");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ OpenSearch client detected, initializing agent");
        }
        builder = ByteBuddyAgentOpenSearch.init(builder);
        builder = ByteBuddyCachedOpenSearchResponse.init(builder);
        builder = ByteBuddyCachedOpenSearchReqest.init(builder);
        return builder;
    }

    /**
     * Инициализирует агенты SQL.
     * SQL драйверы всегда доступны через java.sql.*, поэтому всегда инициализируем.
     */
    public static AgentBuilder initSql(AgentBuilder builder) {
        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ SQL support detected, initializing agent");
        }
        builder = ByteBuddyAgentSqlDriver.init(builder);
        builder = ByteBuddyAgentSql.init(builder);
        return builder;
    }
}
