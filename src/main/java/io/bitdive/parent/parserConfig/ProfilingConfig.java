package io.bitdive.parent.parserConfig;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
public class ProfilingConfig {

    private List<String> notWorkWithSpringProfiles;

    private ApplicationConfig application;
    private List<MonitoringConfig> monitoringConfigs = new ArrayList<>();
    private MonitoringConfig monitoring;
    private AuthorisationConfig authorisation;

    public void detectActualConfig(String[] profileNames) {
        if (ObjectUtils.isNotEmpty(profileNames)) {
            Set<String> activeProfileSet = Arrays.stream(profileNames).collect(Collectors.toSet());

            List<MonitoringConfig> monitoringFilterProfile =
                    monitoringConfigs.stream().filter(monitoringConfig -> {
                        Set<String> activeProfileSetLocal = new HashSet<>(activeProfileSet);
                                Set<String> monitoringProfileSet = Optional.ofNullable(monitoringConfig.getForSpringProfile())
                                        .map(Arrays::stream)
                                        .orElseGet(Stream::empty)
                                        .collect(Collectors.toSet());
                                ;
                        activeProfileSetLocal.retainAll(monitoringProfileSet);
                        return !activeProfileSetLocal.isEmpty();
                            }
                    ).collect(Collectors.toList());
            if (monitoringFilterProfile.size() > 1) {
                throw new IllegalArgumentException("profile Name must not contain more than one monitoring profile");
            }
            if (monitoringFilterProfile.isEmpty()) {
                throw new IllegalArgumentException("monitoring settings cannot be empty for active profile");
            }
            monitoring = monitoringFilterProfile.get(0);
        } else {
            List<MonitoringConfig> monitoringFilterProfile = monitoringConfigs.stream()
                    .filter(monitoringConfig -> monitoringConfig.getForSpringProfile() == null)
                    .collect(Collectors.toList());
            if (monitoringFilterProfile.isEmpty()) {
                throw new IllegalArgumentException("monitoring settings cannot be empty for active profile");
            }
            monitoring = monitoringFilterProfile.get(0);
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
        private boolean enabled = true;

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
            private VaultConfig vault;

            @Getter
            @Setter
            public static class ServerConsumerConfig {
                private String url;
                private ProxyConfig proxy;


                public boolean isSSLSend() {
                    return url.toLowerCase().contains("https");
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

            @Getter
            @Setter
            public static class VaultConfig {
                private String url;
                private String token;
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
