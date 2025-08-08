package io.bitdive.parent.init;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.safety_config.VaultGettingConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MonitoringStarting {
    public static void init() {
        VaultGettingConfig.initVaultConnect();
        Instrumentation instrumentation = ByteBuddyAgent.install();

        ByteBuddyAgentBasic.init(instrumentation);
        ByteBuddyAgentThread.init(instrumentation);
        ByteBuddyAgentThreadCreator.init(instrumentation);
        ByteBuddySimpleClientHttpResponse.init(instrumentation);
        ByteBuddyAgentRestTemplateRequestWeb.init(instrumentation);
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
    }
}
