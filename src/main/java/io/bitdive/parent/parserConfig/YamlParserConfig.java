package io.bitdive.parent.parserConfig;

import com.github.f4b6a3.uuid.UuidCreator;
import lombok.Getter;
import lombok.Setter;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Stream;

public class YamlParserConfig {

    @Setter
    @Getter
    private static String libraryVersion = "";

    @Getter
    @Setter
    private static boolean isWork = true;

    @Getter
    private static final String UUIDService = UuidCreator.getTimeBased().toString();

    @Getter
    private static ProfilingConfig profilingConfig;

    public static void loadConfig() {
        Yaml yaml = new Yaml();

        ProfilingConfig defaultConfig;
        try (InputStream defaultConfigStream = YamlParserConfig.class.getClassLoader().getResourceAsStream("config-profiling-default.yml")) {
            if (defaultConfigStream == null) {
                throw new IllegalArgumentException("File defaults.yml not find");
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

        profilingConfig = finalConfig;

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

            if ((overrideConfig.getMonitoring().getNotWorkWithSpringProfiles() != null) && (overrideConfig.getMonitoring().getNotWorkWithSpringProfiles().length > 0)) {
                baseConfig.getMonitoring().setNotWorkWithSpringProfiles(overrideConfig.getMonitoring().getNotWorkWithSpringProfiles());
            }

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

            if (overrideConfig.getMonitoring().getDataFile() != null) {
                if (overrideConfig.getMonitoring().getDataFile().getPath() != null) {
                    overrideConfig.getMonitoring().getDataFile().setPath(overrideConfig.getMonitoring().getDataFile().getPath());
                }
                if (overrideConfig.getMonitoring().getDataFile().getTimerConvertForSend() != null) {
                    overrideConfig.getMonitoring().getDataFile().setTimerConvertForSend(overrideConfig.getMonitoring().getDataFile().getTimerConvertForSend());
                }
                if (overrideConfig.getMonitoring().getDataFile().getFileStorageTime() != null) {
                    overrideConfig.getMonitoring().getDataFile().setFileStorageTime(overrideConfig.getMonitoring().getDataFile().getFileStorageTime());
                }
            }

            if (overrideConfig.getMonitoring().getSerialization() != null) {
                if (overrideConfig.getMonitoring().getSerialization().getExcludedPackages() != null) {
                    overrideConfig.getMonitoring().getSerialization().setExcludedPackages(
                            Stream.concat(Arrays.stream(overrideConfig.getMonitoring().getSerialization().getExcludedPackages()),
                                            Arrays.stream(baseConfig.getMonitoring().getSerialization().getExcludedPackages())
                                    )
                                    .toArray(String[]::new)
                    );
                }

                if (overrideConfig.getMonitoring().getSerialization().getMaxElementCollection() != null) {
                    overrideConfig.getMonitoring().getSerialization().setMaxElementCollection(baseConfig.getMonitoring().getSerialization().getMaxElementCollection());
                }

            }

            if (overrideConfig.getMonitoring().getSendFiles() != null) {
                if (overrideConfig.getMonitoring().getSendFiles().getSchedulerTimer() != null) {
                    baseConfig.getMonitoring().getSendFiles().setSchedulerTimer(overrideConfig.getMonitoring().getSendFiles().getSchedulerTimer());
                }

                if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer() != null) {
                    if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getUrl() != null) {
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().setUrl(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getUrl());
                    }

                    if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault() != null &&
                            overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getUrl() != null &&
                            overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getLogin() != null &&
                            overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getPassword() != null

                    ) {
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().setVault(new ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.VaultConfig());
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().setUrl(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getUrl());
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().setLogin(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getLogin());
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().setPassword(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getVault().getPassword());

                    }

                    if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy() != null &&
                            overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getHost() != null &&
                            overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getPort() != null

                    ) {

                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().setHost(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getHost());
                        baseConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().setPort(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getPort());

                        if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getUsername() != null) {
                            baseConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().setUsername(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getUsername());
                        }
                        if (overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getPassword() != null) {
                            baseConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().setPassword(overrideConfig.getMonitoring().getSendFiles().getServerConsumer().getProxy().getPassword());
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
