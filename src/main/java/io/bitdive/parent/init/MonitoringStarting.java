package io.bitdive.parent.init;


import io.bitdive.parent.safety_config.VaultGettingConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.utils.ResourceCleanupManager;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.time.Duration;

public class MonitoringStarting {
    public static void init() {
        // КРИТИЧНО: Регистрируем shutdown hook для очистки ресурсов
        ResourceCleanupManager.registerShutdownHook();
        
        VaultGettingConfig.initVaultConnect();
        LoggerStatusContent.initMonitoringDelay(Duration.ofSeconds(60));
        Instrumentation instrumentation = ByteBuddyAgent.install();

        ByteBuddyAgentBasic.init(instrumentation);
        ByteBuddyAgentThread.init(instrumentation);
        ByteBuddyAgentThreadCreator.init(instrumentation);
        ByteBuddySimpleClientHttpResponse.init(instrumentation);
        ByteBuddyAgentRestTemplateRequestWeb.init(instrumentation);
        ByteBuddyAgentCoyoteInputStream.init(instrumentation);  // Captures raw body bytes
        ByteBuddyAgentResponseWeb.init(instrumentation);
        ByteBuddyAgentSql.init(instrumentation);
        ByteBuddyAgentCatalinaResponse.init(instrumentation);
        ByteBuddyAgentFeignRequestWeb.init(instrumentation);
        ByteBuddyAgentSqlDriver.init(instrumentation);
        ByteBuddyAgentKafkaSend.init(instrumentation);
        ByteBuddyAgentKafkaInterceptor.init(instrumentation);
        KafkaConsumerAgent.init(instrumentation);

        ByteBuddyAgentCassandra.init(instrumentation);
        ByteBuddyAgentMongoDelegate.init(instrumentation);
        ByteBuddyAgentRedis.init(instrumentation);
        ByteBuddyAgentNeo4j.init(instrumentation);

        ByteBuddyAgentOpenSearch.init(instrumentation);
        ByteBuddyCachedOpenSearchResponse.init(instrumentation);
        ByteBuddyCachedOpenSearchReqest.init(instrumentation);

        ByteBuddyAgentSoap.init(instrumentation);

        ByteBuddyAgentSpringRawWs.init(instrumentation);
    }
}
