package io.bitdive;


import io.bitdive.parent.jvm_metrics.GenerateJvmMetrics;
import io.bitdive.parent.message_producer.LibraryLoggerConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.utils.LibraryVersionBitDive;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class ByteBuddyAgentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static boolean initializeAgent = false;


    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {

        String[] activeProfiles = applicationContext.getEnvironment().getActiveProfiles();

        if (initializeAgent) {
            return;
        }

        if (isTestEnvironment()) {
            YamlParserConfig.setWork(false);
        }

        YamlParserConfig.loadConfig();
        YamlParserConfig.setLibraryVersion(LibraryVersionBitDive.version);

        if (YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles() != null &&
                !YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles().isEmpty()) {
            Set<String> activeProfileSet = Arrays.stream(activeProfiles).collect(Collectors.toSet());
            Set<String> notWorkProfileSet = Arrays.stream(new String[]{YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles()}).collect(Collectors.toSet());

            activeProfileSet.retainAll(notWorkProfileSet);
            if (!activeProfileSet.isEmpty()) {
                initializeAgent = true;
                YamlParserConfig.setWork(false);
                return;
            }


        }
        YamlParserConfig.getProfilingConfig().detectActualConfig(activeProfiles);
        if (LoggerStatusContent.isDebug()) {
            System.out.println("ByteBuddyAgentInitializer initialize start version: " + YamlParserConfig.getLibraryVersion());
        }
        try {

            if (activeProfiles.length > 0) {
                YamlParserConfig.getProfilingConfig().getApplication().setModuleName(
                        YamlParserConfig.getProfilingConfig().getApplication().getModuleName() + "-" +
                                String.join("-", activeProfiles)
                );
            }
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
            ByteBuddyAgentSqlDriver.init();

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