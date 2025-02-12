package io.bitdive.parent.parserConfig;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfilingConfig {

    private ApplicationConfig application;
    private MonitoringConfig monitoring;
    private AuthorisationConfig authorisation;

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
        private String[] notWorkWithSpringProfiles;

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
