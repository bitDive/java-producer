package io.bitdive;

import io.bitdive.aspect.RepositoryInterceptor;
import io.bitdive.service.LogSenderService;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configurable
@EnableScheduling
public class SpringConfigProfiling {
    @Bean
    public RepositoryInterceptor repositoryInterceptor(){
      return   new RepositoryInterceptor();
    }

    @Bean
    public LogSenderService repositoryMethods() {
        return new LogSenderService(WebClient.builder().build());
    }
}
