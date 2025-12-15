package io.bitdive.parent.init;

import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Умный" lazy loader для агентов БД.
 * Использует ClassFileTransformer для автоматического обнаружения и
 * инструментации классов БД
 * в момент их первой загрузки в JVM, без периодических проверок.
 */
public class SmartLazyDbAgentLoader {

    private static Instrumentation instrumentation;
    private static AgentBuilder baseAgentBuilder;

    // Флаги для отслеживания установленных агентов
    private static final AtomicBoolean cassandraInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean mongoInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean redisInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean neo4jInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean openSearchInstalled = new AtomicBoolean(false);

    /**
     * Инициализирует "умную" отложенную загрузку агентов БД.
     * Устанавливает ClassFileTransformer, который следит за загрузкой классов БД.
     */
    public static void initSmartLazyLoading(Instrumentation inst, AgentBuilder builder) {
        instrumentation = inst;
        baseAgentBuilder = builder;

        // Добавляем трансформер, который срабатывает при загрузке ЛЮБОГО класса
        instrumentation.addTransformer(new LazyDbClassTransformer(), true);

        if (LoggerStatusContent.isDebug()) {
            System.out.println("Smart lazy DB agent loader initialized");
        }
    }

    /**
     * ClassFileTransformer, который перехватывает загрузку классов и устанавливает
     * агенты по требованию.
     */
    private static class LazyDbClassTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) {
                return null;
            }

            // Проверяем, является ли загружаемый класс классом БД
            String normalizedClassName = className.replace('/', '.');

            try {
                // Cassandra
                if (!cassandraInstalled.get() &&
                        normalizedClassName.equals("com.datastax.oss.driver.api.core.CqlSession")) {
                    if (cassandraInstalled.compareAndSet(false, true)) {
                        installCassandraAgentAsync();
                    }
                }

                // MongoDB
                if (!mongoInstalled.get() &&
                        normalizedClassName.equals("com.mongodb.client.internal.MongoClientDelegate")) {
                    if (mongoInstalled.compareAndSet(false, true)) {
                        installMongoAgentAsync();
                    }
                }

                // Redis
                if (!redisInstalled.get() &&
                        normalizedClassName.equals("redis.clients.jedis.Connection")) {
                    if (redisInstalled.compareAndSet(false, true)) {
                        installRedisAgentAsync();
                    }
                }

                // Neo4j
                if (!neo4jInstalled.get() &&
                        normalizedClassName.equals("org.neo4j.driver.internal.InternalSession")) {
                    if (neo4jInstalled.compareAndSet(false, true)) {
                        installNeo4jAgentAsync();
                    }
                }

                // OpenSearch
                if (!openSearchInstalled.get() &&
                        normalizedClassName.equals("org.opensearch.client.RestClient")) {
                    if (openSearchInstalled.compareAndSet(false, true)) {
                        installOpenSearchAgentAsync();
                    }
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error in lazy DB class transformer: " + e.getMessage());
                }
            }

            // Возвращаем null, чтобы не модифицировать байткод класса
            // (модификация произойдет через отдельный агент)
            return null;
        }
    }

    // Асинхронная установка агентов для избежания блокировки загрузки классов
    private static void installCassandraAgentAsync() {
        new Thread(() -> {
            try {
                AgentBuilder agentWithCassandra = ByteBuddyAgentCassandra.init(baseAgentBuilder);
                agentWithCassandra.installOn(instrumentation);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("✓ Cassandra agent installed on-demand");
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error installing Cassandra agent: " + e.getMessage());
                }
            }
        }, "cassandra-agent-installer").start();
    }

    private static void installMongoAgentAsync() {
        new Thread(() -> {
            try {
                AgentBuilder agentWithMongo = ByteBuddyAgentMongoDelegate.init(baseAgentBuilder);
                agentWithMongo.installOn(instrumentation);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("✓ MongoDB agent installed on-demand");
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error installing MongoDB agent: " + e.getMessage());
                }
            }
        }, "mongo-agent-installer").start();
    }

    private static void installRedisAgentAsync() {
        new Thread(() -> {
            try {
                AgentBuilder agentWithRedis = ByteBuddyAgentRedis.init(baseAgentBuilder);
                agentWithRedis.installOn(instrumentation);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("✓ Redis agent installed on-demand");
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error installing Redis agent: " + e.getMessage());
                }
            }
        }, "redis-agent-installer").start();
    }

    private static void installNeo4jAgentAsync() {
        new Thread(() -> {
            try {
                AgentBuilder agentWithNeo4j = ByteBuddyAgentNeo4j.init(baseAgentBuilder);
                agentWithNeo4j.installOn(instrumentation);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("✓ Neo4j agent installed on-demand");
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error installing Neo4j agent: " + e.getMessage());
                }
            }
        }, "neo4j-agent-installer").start();
    }

    private static void installOpenSearchAgentAsync() {
        new Thread(() -> {
            try {
                AgentBuilder agent = ByteBuddyAgentOpenSearch.init(baseAgentBuilder);
                agent = ByteBuddyCachedOpenSearchResponse.init(agent);
                agent = ByteBuddyCachedOpenSearchReqest.init(agent);
                agent.installOn(instrumentation);
                if (LoggerStatusContent.isDebug()) {
                    System.out.println("✓ OpenSearch agent installed on-demand");
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error installing OpenSearch agent: " + e.getMessage());
                }
            }
        }, "opensearch-agent-installer").start();
    }
}
