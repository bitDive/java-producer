package io.bitdive.parent.parserConfig;

import lombok.Getter;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

public class YamlParserConfig {

    @Getter
    private static ProfilingConfig profilingConfig;

    public static void loadConfig() {
        Yaml yaml = new Yaml();

        ProfilingConfig defaultConfig;
        try (InputStream defaultConfigStream = YamlParserConfig.class.getClassLoader().getResourceAsStream("config-profiling-default.yml")) {
            if (defaultConfigStream == null) {
                throw new IllegalArgumentException("Файл defaults.yml не найден");
            }
            defaultConfig = yaml.loadAs(defaultConfigStream, ProfilingConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Error of read file defaults.yml", e);
        }

        ProfilingConfig finalConfig = defaultConfig;

        try (InputStream overrideConfigStream = YamlParserConfig.class.getClassLoader().getResourceAsStream("config-profiling.yml")) {
            if (overrideConfigStream != null) {
                ProfilingConfig overrideConfig = yaml.loadAs(overrideConfigStream, ProfilingConfig.class);
                finalConfig = mergeConfigs(defaultConfig, overrideConfig);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error of read file config-profiling.yml", e);
        }

        profilingConfig =finalConfig;

    }

    public static ProfilingConfig mergeConfigs(ProfilingConfig baseConfig, ProfilingConfig overrideConfig) {
        if (overrideConfig.getApplication() != null) {
            if (overrideConfig.getApplication().getModuleName() != null) {
                baseConfig.getApplication().setModuleName(overrideConfig.getApplication().getModuleName());
            }
            if (overrideConfig.getApplication().getServiceName() != null) {
                baseConfig.getApplication().setServiceName(overrideConfig.getApplication().getServiceName());
            }
            if (overrideConfig.getApplication().getPackedScanner() != null) {
                baseConfig.getApplication().setPackedScanner(overrideConfig.getApplication().getPackedScanner());
            }
        }


        if (overrideConfig.getMonitoring() != null) {
            if (overrideConfig.getMonitoring().getLogLevel() != null) {
                baseConfig.getMonitoring().setLogLevel(overrideConfig.getMonitoring().getLogLevel());
            }
            if (overrideConfig.getMonitoring().getMonitoringArgumentMethod() != null) {
                baseConfig.getMonitoring().setMonitoringArgumentMethod(overrideConfig.getMonitoring().getMonitoringArgumentMethod());
            }
            if (overrideConfig.getMonitoring().getMonitoringReturnMethod() != null) {
                baseConfig.getMonitoring().setMonitoringReturnMethod(overrideConfig.getMonitoring().getMonitoringReturnMethod());
            }
            if (overrideConfig.getMonitoring().getMonitoringStaticMethod() != null) {
                baseConfig.getMonitoring().setMonitoringStaticMethod(overrideConfig.getMonitoring().getMonitoringStaticMethod());
            }
            if (overrideConfig.getMonitoring().getMonitoringOnlySpringComponent() != null) {
                baseConfig.getMonitoring().setMonitoringOnlySpringComponent(overrideConfig.getMonitoring().getMonitoringOnlySpringComponent());
            }

            if (overrideConfig.getMonitoring().getSendMonitoringFiles() != null) {
                if (overrideConfig.getMonitoring().getSendMonitoringFiles().getSchedulerTimer() != null) {
                    baseConfig.getMonitoring().getSendMonitoringFiles().setSchedulerTimer(overrideConfig.getMonitoring().getSendMonitoringFiles().getSchedulerTimer());
                }

                if (overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer() != null) {
                    if (overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getUrl() != null) {
                        baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().setUrl(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getUrl());
                    }

                    if (overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy() != null &&
                            overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getHost() != null &&
                            overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getType() != null &&
                            overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getPort() != null

                    ) {

                        baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().setHost(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getHost());
                        baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().setType(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getType());
                        baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().setPort(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getPort());

                        if (overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getUsername() != null) {
                            baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().setUsername(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getUsername());
                        }
                        if (overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getPassword() != null) {
                            baseConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().setPassword(overrideConfig.getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy().getPassword());
                        }
                    }
                }
            }
        }

        if (overrideConfig.getAuthorisation() != null) {
            if (overrideConfig.getAuthorisation().getToken() != null) {
                baseConfig.getAuthorisation().setToken(overrideConfig.getAuthorisation().getToken());
            }
        }

        return baseConfig;
    }



}
