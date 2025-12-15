package io.bitdive.parent.init;

import io.bitdive.parent.safety_config.VaultGettingConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;
import java.time.Duration;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

public class MonitoringStarting {
        public static void init() {
                registerShutdownHook();

                VaultGettingConfig.initVaultConnect();
                LoggerStatusContent.initMonitoringDelay(Duration.ofSeconds(60));
                Instrumentation instrumentation = ByteBuddyAgent.install();

                AgentBuilder agentStandard = new AgentBuilder.Default()
                                .ignore(
                                                nameStartsWith("javax.")
                                                                .or(nameStartsWith("sun."))
                                                                .or(nameStartsWith("java."))
                                                                .or(nameStartsWith("jdk."))
                                                                .or(nameStartsWith("com.sun.")));

                AgentBuilder agentStandardRetransformation = new AgentBuilder.Default()
                                .ignore(
                                                nameStartsWith("javax.")
                                                                .or(nameStartsWith("sun."))
                                                                .or(nameStartsWith("jdk."))
                                                                .or(nameStartsWith("com.sun.")))
                                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);

                agentStandard = ByteBuddyAgentThreadCreator.init(agentStandard);
                agentStandard = ByteBuddySimpleClientHttpResponse.init(agentStandard);
                agentStandard = ByteBuddyAgentRestTemplateRequestWeb.init(agentStandard);
                agentStandard = ByteBuddyAgentCatalinaResponse.init(agentStandard);
                agentStandard = ByteBuddyAgentFeignRequestWeb.init(agentStandard);

                agentStandardRetransformation = ByteBuddyAgentSqlDriver.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyAgentSqlDriver.init(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentSpringRawWs.init(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentBasic.init(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentCoyoteInputStream.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyAgentResponseWeb.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyAgentSql.init(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentKafkaSend.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyAgentKafkaInterceptor.init(agentStandardRetransformation);
                agentStandardRetransformation = KafkaConsumerAgent.init(agentStandardRetransformation);

                // Только автоматическая проверка драйверов БД - безопасная оптимизация
                agentStandardRetransformation = OptimizedDbAgents.initCassandra(agentStandardRetransformation);
                agentStandardRetransformation = OptimizedDbAgents.initMongo(agentStandardRetransformation);
                agentStandardRetransformation = OptimizedDbAgents.initRedis(agentStandardRetransformation);
                agentStandardRetransformation = OptimizedDbAgents.initNeo4j(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentOpenSearch.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyCachedOpenSearchResponse.init(agentStandardRetransformation);
                agentStandardRetransformation = ByteBuddyCachedOpenSearchReqest.init(agentStandardRetransformation);

                agentStandardRetransformation = ByteBuddyAgentSoap.init(agentStandardRetransformation);

                ByteBuddyAgentThread.init(instrumentation);

                agentStandard.installOn(instrumentation);
                agentStandardRetransformation.installOn(instrumentation);

        }

        /**
         * КРИТИЧНО: Регистрация shutdown hook для корректной остановки всех ресурсов
         */
        private static void registerShutdownHook() {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                                // Останавливаем Vault scheduler
                                VaultGettingConfig.shutdown();
                        } catch (Exception e) {
                                System.err.println("Error shutting down Vault scheduler: " + e.getMessage());
                        }

                        try {
                                // Останавливаем monitoring delay scheduler (если еще работает)
                                LoggerStatusContent.shutdownScheduler();
                        } catch (Exception e) {
                                System.err.println("Error shutting down monitoring scheduler: " + e.getMessage());
                        }

                        try {
                                // Останавливаем lazy DB agent loader scheduler
                                LazyDbAgentLoader.shutdown();
                        } catch (Exception e) {
                                System.err.println("Error shutting down lazy DB agent loader: " + e.getMessage());
                        }
                }, "BitDive-Shutdown-Hook"));
        }
}
