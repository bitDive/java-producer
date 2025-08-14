package io.bitdive;


import io.bitdive.jvm_metrics.GenerateJvmMetrics;
import io.bitdive.parent.init.MonitoringStarting;
import io.bitdive.parent.message_producer.LibraryLoggerConfig;
import io.bitdive.parent.parserConfig.ConfigForServiceDTO;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.utils.ByteBuddyConfigLoader;
import io.bitdive.parent.utils.LibraryVersionBitDive;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ByteBuddyAgentInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static boolean initializeAgent = false;

    private static ScheduledExecutorService scheduler;

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        try {
            ConfigurableEnvironment env = applicationContext.getEnvironment();
            String[] activeProfiles = env.getActiveProfiles();

            if (initializeAgent) {
                return;
            }

            if (isTestEnvironment()) {
                YamlParserConfig.setWork(false);
                return;
            }
            ConfigForServiceDTO configForServiceDTO = ByteBuddyConfigLoader.load();

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "minute-task");
                t.setDaemon(true);
                return t;
            });

            final ConfigForServiceDTO configForServiceDTOFinal = configForServiceDTO;

            Runnable task = () -> {
                try {
                    if (initializeAgent || YamlParserConfig.isWork()) {
                        YamlParserConfig.setWork(false);
                        YamlParserConfig.loadConfig(configForServiceDTOFinal);
                        YamlParserConfig.getProfilingConfig().detectActualConfig(activeProfiles);
                        LibraryLoggerConfig.init();
                        GenerateJvmMetrics.init();
                        if (LoggerStatusContent.isDebug()) {
                            System.err.println("Minute task reload config ");
                        }
                        YamlParserConfig.setWork(true);
                    }
                } catch (Throwable ex) {
                }
            };


            scheduler.scheduleAtFixedRate(task, 1, 1, TimeUnit.MINUTES);

            YamlParserConfig.loadConfig(configForServiceDTO);
            YamlParserConfig.setLibraryVersion(LibraryVersionBitDive.version);


            if (YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles() != null &&
                    !YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles().isEmpty()) {
                Set<String> activeProfileSet = Arrays.stream(activeProfiles).collect(Collectors.toSet());
                Set<String> notWorkProfileSet = new HashSet<>(YamlParserConfig.getProfilingConfig().getNotWorkWithSpringProfiles());

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


            if (activeProfiles.length > 0) {
                YamlParserConfig.getProfilingConfig().getApplication().setModuleName(
                        YamlParserConfig.getProfilingConfig().getApplication().getModuleName() + "-" +
                                String.join("-", activeProfiles)
                );
            }


            LibraryLoggerConfig.init();
            MonitoringStarting.init();
            GenerateJvmMetrics.init();

            initializeAgent = true;
            YamlParserConfig.setWork(true);

        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                initializeAgent = true;
                YamlParserConfig.setWork(false);
                System.out.println("ByteBuddyAgentInitializer initialize error " + e.getMessage());
            }
        }
    }

    private boolean isTestEnvironment() {
        try {
            if ("test".equalsIgnoreCase(System.getProperty("env")) ||
                    "test".equalsIgnoreCase(System.getenv("ENV"))
            ) return true;
            Class.forName("org.junit.jupiter.api.Test");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}