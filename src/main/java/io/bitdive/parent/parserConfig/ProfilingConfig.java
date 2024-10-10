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
        private SendMonitoringFilesConfig sendMonitoringFiles;

        @Getter
        @Setter
        public static class SendMonitoringFilesConfig {
            private ServerConsumerConfig serverConsumer;
            private Long schedulerTimer;

            @Getter
            @Setter
            public static class ServerConsumerConfig {
                private String url;
                private ProxyConfig proxy;

                @Getter
                @Setter
                public static class ProxyConfig {
                    private String type; /*HTTP,SOCKS4,SOCKS5;*/
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
        private String packedScanner;
    }

    @Getter
    @Setter
    public static class AuthorisationConfig {
        private String token;
    }
}
