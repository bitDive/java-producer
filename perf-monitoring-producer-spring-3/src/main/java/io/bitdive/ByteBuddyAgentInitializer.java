package io.bitdive;


import io.bitdive.parent.jvm_metrics.GenerateJvmMetrics;
import io.bitdive.parent.message_producer.LibraryLoggerConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class ByteBuddyAgentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static boolean initializeAgent = false;


    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {

        if (initializeAgent || isTestEnvironment()) {
            return;
        }
        YamlParserConfig.loadConfig();
        YamlParserConfig.setLibraryVersion("0.0.14");
        if (LoggerStatusContent.isDebug()) {
            System.out.println("ByteBuddyAgentInitializer initialize start version: " + YamlParserConfig.getLibraryVersion());
        }
        try {
            LibraryLoggerConfig.init();
            ByteBuddyAgent.install();
            ByteBuddyAgentBasic.init();
            ByteBuddyAgentThread.init();
            ByteBuddyAgentThreadCreator.init();
            ByteBuddySimpleClientHttpResponse.init();
            ByteBuddyAgentRestTemplateRequestWeb.init();
            ByteBuddyAgentResponseWeb.init();
            ByteBuddyAgentSql.init();
            ByteBuddyAgentCatalinaResponse.init();
            ByteBuddyAgentFeignRequestWeb.init();
            ByteBuddySimpleClientHttpResponse.init();

            GenerateJvmMetrics.init();

            initializeAgent = true;
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("ByteBuddyAgentInitializer initialize " + e.getMessage());
            }
        }
    }

    private boolean isTestEnvironment() {
        try {
            Class.forName("org.junit.jupiter.api.Test");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}