package io.bitdive;

import io.bitdive.parent.aspect.FeignClientAspect;
import io.bitdive.parent.aspect.RepositoryAspect;
import io.bitdive.parent.aspect.SchedulerAspect;
import io.bitdive.parent.aspect.YamlParserCondition;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configurable
@EnableScheduling
public class SpringConfigProfiling {

    @Bean
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    @Conditional(YamlParserCondition.class)
    public RepositoryAspect repositoryInterceptor() {
        return new RepositoryAspect();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
    @Conditional(YamlParserCondition.class)
    public FeignClientAspect feignClientInterceptor() {

        return new FeignClientAspect();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.scheduling.annotation.Scheduled")
    @Conditional(YamlParserCondition.class)
    public SchedulerAspect schedulerAspect() {
        return new SchedulerAspect();
    }

}
