package io.bitdive.parent.parserConfig;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
public class ProfilingConfig {

    private String notWorkWithSpringProfiles;

    private ApplicationConfig application;
    private List<MonitoringConfig> monitoringConfigs = new ArrayList<>();
    private MonitoringConfig monitoring;
    private AuthorisationConfig authorisation;

    public void detectActualConfig(String[] profileNames) {
        if (ObjectUtils.isEmpty(profileNames) && monitoringConfigs.size() > 1) {
            if (monitoringConfigs.stream()
                    .filter(monitoringConfig -> ObjectUtils.isEmpty(monitoringConfig.getForSpringProfile()))
                    .count() != 1) {
                throw new IllegalArgumentException("profile Name must not be null or empty because monitoring more than once");
            } else {
                monitoring = monitoringConfigs.get(0);
            }
        }
        if (monitoringConfigs.isEmpty()) {
            throw new IllegalArgumentException("monitoring settings cannot be empty");
        }

        if (monitoringConfigs.size() == 1 && ObjectUtils.isEmpty(profileNames)) {
            monitoring = monitoringConfigs.get(0);
            return;
        }

        if (monitoringConfigs.size() == 1 && !ObjectUtils.isEmpty(profileNames)) {
            if (ObjectUtils.isEmpty(monitoringConfigs.get(0).getForSpringProfile())) {
                monitoring = monitoringConfigs.get(0);
                return;
            }

        }

        if (!ObjectUtils.isEmpty(profileNames)) {
            Set<String> activeProfileSet = Arrays.stream(profileNames).collect(Collectors.toSet());


            List<MonitoringConfig> monitoringFilterProfile =
                    monitoringConfigs.stream().filter(monitoringConfig -> {
                                Set<String> monitoringProfileSet = Optional.ofNullable(monitoringConfig.getForSpringProfile())
                                        .map(Arrays::stream)
                                        .orElseGet(Stream::empty)
                                        .collect(Collectors.toSet());
                                ;
                                activeProfileSet.retainAll(monitoringProfileSet);
                                return !activeProfileSet.isEmpty();
                            }
                    ).collect(Collectors.toList());
            if (monitoringFilterProfile.size() > 1) {
                throw new IllegalArgumentException("profile Name must not contain more than one monitoring profile");
            }
            if (monitoringFilterProfile.isEmpty()) {
                throw new IllegalArgumentException("monitoring settings cannot be empty for active profile");
            }
            monitoring = monitoringFilterProfile.get(0);
            return;
        }


    }

    @Getter
    @Setter
    public static class MonitoringConfig {
        private LogLevelEnum logLevel;
        private Boolean monitoringArgumentMethod;
        private Boolean monitoringReturnMethod;
        private Boolean monitoringStaticMethod;
        private Boolean monitoringOnlySpringComponent;
        private MonitoringSendFilesConfig sendFiles;
        private MonitoringDataFile dataFile;
        private Serialization serialization;

        private String[] forSpringProfile;

        @Getter
        @Setter
        public static class Serialization {
            private String[] excludedPackages;
            private Integer maxElementCollection;
        }

        @Getter
        @Setter
        public static class MonitoringDataFile {
            private String path;
            private Integer timerConvertForSend;
            private Integer fileStorageTime;

        }

        @Getter
        @Setter
        public static class MonitoringSendFilesConfig {
            private ServerConsumerConfig serverConsumer;
            private Long schedulerTimer;


            @Getter
            @Setter
            public static class ServerConsumerConfig {
                private String url;
                private ProxyConfig proxy;
                private VaultConfig vault;

                public boolean isSSLSend() {
                    return url.toLowerCase().contains("https");
                }

                @Getter
                @Setter
                public static class VaultConfig {
                    private String url;
                    private String login;
                    private String password;
                }

                @Getter
                @Setter
                public static class ProxyConfig {
                    private String host;
                    private Integer port;
                    private String username;
                    private String password;
                }
            }
        }
    }

    @Getter
    @Setter
    public static class ApplicationConfig {
        private String moduleName;
        private String serviceName;
        private String[] packedScanner;
    }

    @Getter
    @Setter
    public static class AuthorisationConfig {
        private String token;
    }
}
