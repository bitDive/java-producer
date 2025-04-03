package io.bitdive.parent.init;

import io.bitdive.parent.jvm_metrics.GenerateJvmMetrics;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;

public class MonitoringStarting {
    public static void init() {
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
        ByteBuddySimpleClientHttpResponse.init(instrumentation);
        ByteBuddyAgentSqlDriver.init(instrumentation);
        ByteBuddyAgentKafkaSend.init(instrumentation);
        ByteBuddyAgentKafkaInterceptor.init(instrumentation);
        KafkaConsumerAgent.init(instrumentation);

        GenerateJvmMetrics.init();
    }
}
