package io.bitdive.parent.init;

/**
 * MonitoringStarting class is responsible for initializing the monitoring system
 * by setting up various ByteBuddy agents for instrumenting different components
 * such as HTTP requests, database operations, messaging, and more.
 */
import io.bitdive.parent.safety_config.VaultGettingConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;

public class MonitoringStarting {
    /**
     * Initializes the monitoring system by installing ByteBuddy agents
     * for various components to enable tracing and profiling.
     */
    public static void init() {
        // Initialize Vault configuration connection
        VaultGettingConfig.initVaultConnect();
        
        // Install ByteBuddy agent for instrumentation
        Instrumentation instrumentation = ByteBuddyAgent.install();

        // Initialize basic agent for general monitoring
        ByteBuddyAgentBasic.init(instrumentation);
        
        // Initialize thread-related agents
        ByteBuddyAgentThread.init(instrumentation);
        ByteBuddyAgentThreadCreator.init(instrumentation);
        
        // Initialize HTTP client and response agents
        ByteBuddySimpleClientHttpResponse.init(instrumentation);
        ByteBuddyAgentRestTemplateRequestWeb.init(instrumentation);
        
        // Initialize Tomcat-specific agents
        ByteBuddyAgentCoyoteInputStream.init(instrumentation);  // Captures raw body bytes
        ByteBuddyAgentResponseWeb.init(instrumentation);
        
        // Initialize SQL and database agents
        ByteBuddyAgentSql.init(instrumentation);
        ByteBuddyAgentCatalinaResponse.init(instrumentation);
        ByteBuddyAgentFeignRequestWeb.init(instrumentation);
        ByteBuddyAgentSqlDriver.init(instrumentation);
        
        // Initialize messaging agents (Kafka)
        ByteBuddyAgentKafkaSend.init(instrumentation);
        ByteBuddyAgentKafkaInterceptor.init(instrumentation);
        KafkaConsumerAgent.init(instrumentation);

        // Initialize NoSQL database agents
        ByteBuddyAgentCassandra.init(instrumentation);
        ByteBuddyAgentMongoDelegate.init(instrumentation);
        ByteBuddyAgentRedis.init(instrumentation);
        ByteBuddyAgentNeo4j.init(instrumentation);

        // Initialize search engine agents (OpenSearch)
        ByteBuddyAgentOpenSearch.init(instrumentation);
        ByteBuddyCachedOpenSearchResponse.init(instrumentation);
        ByteBuddyCachedOpenSearchReqest.init(instrumentation);

        // Initialize SOAP web service agent
        ByteBuddyAgentSoap.init(instrumentation);

        // Initialize Spring WebSocket agent
        ByteBuddyAgentSpringRawWs.init(instrumentation);
    }
}
