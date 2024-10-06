package io.bitdive.parent.parserConfig;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfilingConfig {

    private ApplicationConfig application;
    private MessageBrokerConfig messageBroker;
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
        private String pathMonitoringFilesSave;

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
                    private String url;
                    private String login;
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
    public static class MessageBrokerConfig {
        private String bootstrapServers;
    }

    @Getter
    @Setter
    public static class AuthorisationConfig {
        private String token;
    }
}
