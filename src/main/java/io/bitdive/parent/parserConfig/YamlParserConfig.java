package io.bitdive.parent.parserConfig;

import com.github.f4b6a3.uuid.UuidCreator;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.ObjectUtils;
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
        if (!ObjectUtils.isEmpty(overrideConfig.getNotWorkWithSpringProfiles())) {
            baseConfig.setNotWorkWithSpringProfiles(overrideConfig.getNotWorkWithSpringProfiles());
        }

        if (overrideConfig.getMonitoringConfigs() != null && !overrideConfig.getMonitoringConfigs().isEmpty()) {
            for (ProfilingConfig.MonitoringConfig monitoringConfigOverride : overrideConfig.getMonitoringConfigs()) {

                ProfilingConfig.MonitoringConfig defaultVal = DefaultMonitoringValues.create();

                if (monitoringConfigOverride.getForSpringProfile() != null) {
                    defaultVal.setForSpringProfile(monitoringConfigOverride.getForSpringProfile());
                }

                if (monitoringConfigOverride.getLogLevel() != null) {
                    defaultVal.setLogLevel(monitoringConfigOverride.getLogLevel());
                }
                if (monitoringConfigOverride.getMonitoringArgumentMethod() != null) {
                    defaultVal.setMonitoringArgumentMethod(monitoringConfigOverride.getMonitoringArgumentMethod());
                }
                if (monitoringConfigOverride.getMonitoringReturnMethod() != null) {
                    defaultVal.setMonitoringReturnMethod(monitoringConfigOverride.getMonitoringReturnMethod());
                }
                if (monitoringConfigOverride.getMonitoringStaticMethod() != null) {
                    defaultVal.setMonitoringStaticMethod(monitoringConfigOverride.getMonitoringStaticMethod());
                }
                if (monitoringConfigOverride.getMonitoringOnlySpringComponent() != null) {
                    defaultVal.setMonitoringOnlySpringComponent(monitoringConfigOverride.getMonitoringOnlySpringComponent());
                }

                if (monitoringConfigOverride.getDataFile() != null) {
                    if (monitoringConfigOverride.getDataFile().getPath() != null) {
                        defaultVal.getDataFile().setPath(monitoringConfigOverride.getDataFile().getPath());
                    }
                    if (monitoringConfigOverride.getDataFile().getTimerConvertForSend() != null) {
                        defaultVal.getDataFile().setTimerConvertForSend(monitoringConfigOverride.getDataFile().getTimerConvertForSend());
                    }
                    if (monitoringConfigOverride.getDataFile().getFileStorageTime() != null) {
                        defaultVal.getDataFile().setFileStorageTime(monitoringConfigOverride.getDataFile().getFileStorageTime());
                    }
                }

                if (monitoringConfigOverride.getSerialization() != null) {
                    if (monitoringConfigOverride.getSerialization().getExcludedPackages() != null) {
                        defaultVal.getSerialization().setExcludedPackages(
                                Stream.concat(Arrays.stream(monitoringConfigOverride.getSerialization().getExcludedPackages()),
                                                Arrays.stream(defaultVal.getSerialization().getExcludedPackages())
                                        )
                                        .toArray(String[]::new)
                        );
                    }

                    if (monitoringConfigOverride.getSerialization().getMaxElementCollection() != null) {
                        defaultVal.getSerialization().setMaxElementCollection(monitoringConfigOverride.getSerialization().getMaxElementCollection());
                    }

                }

                if (monitoringConfigOverride.getSendFiles() != null) {
                    if (monitoringConfigOverride.getSendFiles().getSchedulerTimer() != null) {
                        defaultVal.getSendFiles().setSchedulerTimer(monitoringConfigOverride.getSendFiles().getSchedulerTimer());
                    }

                    if (monitoringConfigOverride.getSendFiles().getServerConsumer() != null) {
                        if (monitoringConfigOverride.getSendFiles().getServerConsumer().getUrl() != null) {
                            defaultVal.getSendFiles().getServerConsumer().setUrl(monitoringConfigOverride.getSendFiles().getServerConsumer().getUrl());
                        }

                        if (monitoringConfigOverride.getSendFiles().getServerConsumer().getVault() != null &&
                                monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getUrl() != null &&
                                monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getLogin() != null &&
                                monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getPassword() != null

                        ) {
                            defaultVal.getSendFiles().getServerConsumer().setVault(new ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.VaultConfig());
                            defaultVal.getSendFiles().getServerConsumer().getVault().setUrl(monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getUrl());
                            defaultVal.getSendFiles().getServerConsumer().getVault().setLogin(monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getLogin());
                            defaultVal.getSendFiles().getServerConsumer().getVault().setPassword(monitoringConfigOverride.getSendFiles().getServerConsumer().getVault().getPassword());

                        }

                        if (monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy() != null &&
                                monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getHost() != null &&
                                monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getPort() != null

                        ) {

                            defaultVal.getSendFiles().getServerConsumer().getProxy().setHost(monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getHost());
                            defaultVal.getSendFiles().getServerConsumer().getProxy().setPort(monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getPort());

                            if (monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getUsername() != null) {
                                defaultVal.getSendFiles().getServerConsumer().getProxy().setUsername(monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getUsername());
                            }
                            if (monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getPassword() != null) {
                                defaultVal.getSendFiles().getServerConsumer().getProxy().setPassword(monitoringConfigOverride.getSendFiles().getServerConsumer().getProxy().getPassword());
                            }
                        }
                    }
                }

                baseConfig.getMonitoringConfigs().add(defaultVal);
            }
        }

       /* if (overrideConfig.getAuthorisation() != null) {
            if (overrideConfig.getAuthorisation().getToken() != null) {
                baseConfig.getAuthorisation().setToken(overrideConfig.getAuthorisation().getToken());
            }
        }*/

        return baseConfig;
    }

}
