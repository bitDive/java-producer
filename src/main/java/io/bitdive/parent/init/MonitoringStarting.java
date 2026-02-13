package io.bitdive.parent.init;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.safety_config.VaultGettingConfig;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.*;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchReqest;
import io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached.ByteBuddyCachedOpenSearchResponse;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.utils.Pair;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

public class MonitoringStarting {

        public static void init() {
                registerShutdownHook();

                VaultGettingConfig.initVaultConnect();
                LoggerStatusContent.initMonitoringDelay(Duration.ofSeconds(1));
                Instrumentation instrumentation = ByteBuddyAgent.install();

                File bootstrapTemp = new File(System.getProperty("java.io.tmpdir"), "bitdive-bootstrap");
                bootstrapTemp.mkdirs();

                AgentBuilder agentJavaStandard = new AgentBuilder.Default()
                                .disableClassFormatChanges()
                                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                                .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(instrumentation, bootstrapTemp));


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
              //  agentStandard = ByteBuddySimpleClientHttpResponse.init(agentStandard);

                Pair<AgentBuilder,AgentBuilder> retPair = ByteBuddyAgentRestTemplateRequestWeb.init(agentStandard,agentStandardRetransformation);
                agentStandard=retPair.getKey();
                agentStandardRetransformation=retPair.getVal();

                agentStandard = ByteBuddyAgentCatalinaResponse.init(agentStandard);
                agentStandard = ByteBuddyAgentFeignEncoderRequestBody.init(agentStandard);
                agentStandard = ByteBuddyAgentFeignRequestWeb.init(agentStandard);
                agentStandardRetransformation = ByteBuddyAgentFeignEncoderRequestBody.init(agentStandardRetransformation);

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



                injectIntoBootstrap(instrumentation, bootstrapTemp, NowRandomSpyCache.class);
                agentJavaStandard=NowRandomSpyAgent.init(agentJavaStandard);

                agentJavaStandard.installOn(instrumentation);
                agentStandard.installOn(instrumentation);
                agentStandardRetransformation.installOn(instrumentation);

        }

        /**
         * Инжектит класс в bootstrap classloader через {@link Instrumentation#appendToBootstrapClassLoaderSearch}.
         * Создаёт временный JAR с байткодом класса и добавляет его в bootstrap classpath.
         */
        private static void injectIntoBootstrap(Instrumentation instrumentation, File tempDir, Class<?> clazz) {
                String classResource = clazz.getName().replace('.', '/') + ".class";
                try {
                        File tempJar = File.createTempFile("bitdive-bootstrap-", ".jar", tempDir);
                        tempJar.deleteOnExit();
                        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {
                                jos.putNextEntry(new JarEntry(classResource));
                                try (InputStream is = clazz.getClassLoader().getResourceAsStream(classResource)) {
                                        if (is == null) throw new FileNotFoundException(classResource);
                                        byte[] buf = new byte[4096];
                                        int n;
                                        while ((n = is.read(buf)) != -1) {
                                                jos.write(buf, 0, n);
                                        }
                                }
                                jos.closeEntry();
                        }
                        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar));
                } catch (IOException e) {
                        System.err.println("[BitDive] Failed to inject " + clazz.getName() + " into bootstrap CL: " + e);
                }
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
