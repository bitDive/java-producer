package io.bitdive.parent.init;

import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Умная обертка для других агентов (Kafka, SOAP, Feign) с автоматической
 * проверкой.
 * АВТОМАТИЧЕСКИ определяет доступность библиотек и инициализирует только нужные
 * агенты.
 */
public class OptimizedOtherAgents {

    /**
     * Проверяет наличие класса в classpath.
     */
    private static boolean isLibraryAvailable(String className) {
        try {
            Class.forName(className, false, OptimizedOtherAgents.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Инициализирует Kafka агенты только если Kafka клиент присутствует в
     * classpath.
     */
    public static AgentBuilder initKafka(AgentBuilder builder) {
        if (!isLibraryAvailable("org.apache.kafka.clients.producer.KafkaProducer")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Kafka client not found in classpath, skipping Kafka agents");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Kafka client detected, initializing agents");
        }

        builder = ByteBuddyAgentKafkaSend.init(builder);
        builder = ByteBuddyAgentKafkaInterceptor.init(builder);
        builder = KafkaConsumerAgent.init(builder);
        return builder;
    }

    /**
     * Инициализирует SOAP агент только если SOAP библиотеки присутствуют в
     * classpath.
     */
    public static AgentBuilder initSoap(AgentBuilder builder) {
        // Проверяем наличие Spring WS или JAX-WS
        boolean hasSpringWs = isLibraryAvailable("org.springframework.ws.client.core.WebServiceTemplate");
        boolean hasJaxWs = isLibraryAvailable("javax.xml.ws.Service") ||
                isLibraryAvailable("jakarta.xml.ws.Service");

        if (!hasSpringWs && !hasJaxWs) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ SOAP libraries not found in classpath, skipping SOAP agent");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ SOAP libraries detected, initializing agent");
        }
        return ByteBuddyAgentSoap.init(builder);
    }

    /**
     * Инициализирует Feign агент только если Feign присутствует в classpath.
     */
    public static AgentBuilder initFeign(AgentBuilder builder) {
        if (!isLibraryAvailable("feign.Client")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Feign client not found in classpath, skipping Feign agent");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Feign client detected, initializing agent");
        }
        return ByteBuddyAgentFeignRequestWeb.init(builder);
    }

    /**
     * Инициализирует WebSocket агент только если Spring WebSocket присутствует в
     * classpath.
     */
    public static AgentBuilder initWebSocket(AgentBuilder builder) {
        if (!isLibraryAvailable("org.springframework.web.socket.WebSocketHandler")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ Spring WebSocket not found in classpath, skipping WebSocket agent");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ Spring WebSocket detected, initializing agent");
        }
        return ByteBuddyAgentSpringRawWs.init(builder);
    }

    /**
     * Инициализирует RestTemplate агент.
     * RestTemplate обычно всегда доступен в Spring приложениях.
     */
    public static AgentBuilder initRestTemplate(AgentBuilder builder) {
        if (!isLibraryAvailable("org.springframework.web.client.RestTemplate")) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("⊘ RestTemplate not found in classpath, skipping agent");
            }
            return builder;
        }

        if (LoggerStatusContent.isDebug()) {
            System.out.println("✓ RestTemplate detected, initializing agent");
        }
        return ByteBuddyAgentRestTemplateRequestWeb.init(builder);
    }
}
