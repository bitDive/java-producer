package io.bitdive.parent.init;

import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy loader для агентов БД.
 * Инициализирует агенты БД только когда соответствующие классы действительно
 * загружаются в JVM.
 */
public class LazyDbAgentLoader {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lazy-db-agent-loader");
        t.setDaemon(true);
        return t;
    });

    // Флаги для отслеживания установленных агентов
    private static final AtomicBoolean cassandraInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean mongoInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean redisInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean neo4jInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean openSearchInstalled = new AtomicBoolean(false);

    /**
     * Запускает асинхронную проверку наличия классов БД и устанавливает агенты по
     * требованию.
     * 
     * @param instrumentation Java Instrumentation
     * @param agentBuilder    Базовый AgentBuilder с настройками retransformation
     */
    public static void initLazyLoading(Instrumentation instrumentation, AgentBuilder agentBuilder) {
        // Запускаем периодическую проверку каждые 5 секунд
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndInstallAgents(instrumentation, agentBuilder);
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error in lazy DB agent loader: " + e.getMessage());
                }
            }
        }, 2, 5, TimeUnit.SECONDS); // Первая проверка через 2 секунды, затем каждые 5 секунд

        // Останавливаем scheduler через 60 секунд (все классы должны быть загружены)
        scheduler.schedule(() -> {
            scheduler.shutdown();
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Lazy DB agent loader scheduler stopped");
            }
        }, 60, TimeUnit.SECONDS);
    }

    /**
     * Проверяет наличие классов БД в ClassLoader и устанавливает соответствующие
     * агенты.
     */
    private static void checkAndInstallAgents(Instrumentation instrumentation, AgentBuilder agentBuilder) {
        // Cassandra
        if (!cassandraInstalled.get() && isClassLoaded("com.datastax.oss.driver.api.core.CqlSession")) {
            if (cassandraInstalled.compareAndSet(false, true)) {
                installCassandraAgent(instrumentation, agentBuilder);
            }
        }

        // MongoDB
        if (!mongoInstalled.get() && isClassLoaded("com.mongodb.client.internal.MongoClientDelegate")) {
            if (mongoInstalled.compareAndSet(false, true)) {
                installMongoAgent(instrumentation, agentBuilder);
            }
        }

        // Redis
        if (!redisInstalled.get() && isClassLoaded("redis.clients.jedis.Connection")) {
            if (redisInstalled.compareAndSet(false, true)) {
                installRedisAgent(instrumentation, agentBuilder);
            }
        }

        // Neo4j
        if (!neo4jInstalled.get() && isClassLoaded("org.neo4j.driver.internal.InternalSession")) {
            if (neo4jInstalled.compareAndSet(false, true)) {
                installNeo4jAgent(instrumentation, agentBuilder);
            }
        }

        // OpenSearch
        if (!openSearchInstalled.get() && isClassLoaded("org.opensearch.client.RestClient")) {
            if (openSearchInstalled.compareAndSet(false, true)) {
                installOpenSearchAgent(instrumentation, agentBuilder);
            }
        }
    }

    /**
     * Проверяет, загружен ли класс в ClassLoader без его принудительной загрузки.
     */
    private static boolean isClassLoaded(String className) {
        try {
            // Используем getLoadedClasses для проверки без загрузки класса
            Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
            for (Class<?> clazz : loadedClasses) {
                if (clazz.getName().equals(className)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static Instrumentation instrumentation;

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    // Методы установки агентов
    private static void installCassandraAgent(Instrumentation inst, AgentBuilder builder) {
        try {
            AgentBuilder agentWithCassandra = ByteBuddyAgentCassandra.init(builder);
            agentWithCassandra.installOn(inst);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Cassandra agent installed lazily");
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error installing Cassandra agent: " + e.getMessage());
            }
        }
    }

    private static void installMongoAgent(Instrumentation inst, AgentBuilder builder) {
        try {
            AgentBuilder agentWithMongo = ByteBuddyAgentMongoDelegate.init(builder);
            agentWithMongo.installOn(inst);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("MongoDB agent installed lazily");
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error installing MongoDB agent: " + e.getMessage());
            }
        }
    }

    private static void installRedisAgent(Instrumentation inst, AgentBuilder builder) {
        try {
            AgentBuilder agentWithRedis = ByteBuddyAgentRedis.init(builder);
            agentWithRedis.installOn(inst);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Redis agent installed lazily");
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error installing Redis agent: " + e.getMessage());
            }
        }
    }

    private static void installNeo4jAgent(Instrumentation inst, AgentBuilder builder) {
        try {
            AgentBuilder agentWithNeo4j = ByteBuddyAgentNeo4j.init(builder);
            agentWithNeo4j.installOn(inst);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Neo4j agent installed lazily");
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error installing Neo4j agent: " + e.getMessage());
            }
        }
    }

    private static void installOpenSearchAgent(Instrumentation inst, AgentBuilder builder) {
        try {
            AgentBuilder agent = ByteBuddyAgentOpenSearch.init(builder);
            agent = ByteBuddyCachedOpenSearchResponse.init(agent);
            agent = ByteBuddyCachedOpenSearchReqest.init(agent);
            agent.installOn(inst);
            if (LoggerStatusContent.isDebug()) {
                System.out.println("OpenSearch agent installed lazily");
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Error installing OpenSearch agent: " + e.getMessage());
            }
        }
    }

    /**
     * Останавливает scheduler при shutdown приложения
     */
    public static void shutdown() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
