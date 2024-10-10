package io.bitdive;

import io.bitdive.aspect.RepositoryInterceptor;
import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.service.MonitoringSenderService;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;


@Configurable
@EnableScheduling
public class SpringConfigProfiling {
    @Bean
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    public RepositoryInterceptor repositoryInterceptor(){
      return   new RepositoryInterceptor();
    }

    @Bean
    public MonitoringSenderService repositoryMethods() {

        WebClient.Builder webClientBuilder = WebClient.builder();
        ProfilingConfig.MonitoringConfig.SendMonitoringFilesConfig.ServerConsumerConfig.ProxyConfig
                proxyConfig = YamlParserConfig.getProfilingConfig().getMonitoring().getSendMonitoringFiles().getServerConsumer().getProxy();
        if (proxyConfig != null) {
            HttpClient httpClient = null;
            if (proxyConfig.getUsername() != null && proxyConfig.getPassword() != null) {
                HttpClient.create()
                        .proxy(proxy ->
                                proxy
                                        .type(ProxyProvider.Proxy.valueOf(proxyConfig.getType())) // HTTP, SOCKS4, SOCKS5
                                        .host(proxyConfig.getHost())
                                        .port(proxyConfig.getPort())
                                        .username(proxyConfig.getUsername())
                                        .password(s -> proxyConfig.getPassword())
                        );

            } else {
                httpClient = HttpClient.create()
                        .proxy(proxy ->
                                proxy
                                        .type(ProxyProvider.Proxy.valueOf(proxyConfig.getType())) // HTTP, SOCKS4, SOCKS5
                                        .host(proxyConfig.getHost())
                                        .port(proxyConfig.getPort())
                        );
            }

            assert httpClient != null;
            ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

            webClientBuilder.clientConnector(connector);
        }
        return new MonitoringSenderService(webClientBuilder.build());
    }
}
