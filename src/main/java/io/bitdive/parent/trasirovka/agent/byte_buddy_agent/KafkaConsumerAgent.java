package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.KafkaAgentStorage;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Optional;

public class KafkaConsumerAgent {
    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.apache.kafka.clients.consumer.KafkaConsumer"))
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder.constructor(ElementMatchers.any()
                                       /* ElementMatchers.takesArguments(1)
                                                .and(ElementMatchers.takesArgument(0, Map.class))*/
                                )
                                .intercept(Advice.to(KafkaConsumerConstructorAdvice.class))
                )
                .installOn(instrumentation);
    }

    public static class KafkaConsumerConstructorAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.AllArguments Object[] allArgs) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                Object bootstrapServers = null;
                if (allArgs.length > 0) {
                    Object arg0 = allArgs[0];
                    if (arg0 instanceof Map) {
                        Map<?, ?> configs = (Map<?, ?>) arg0;
                        bootstrapServers = configs.get("bootstrap.servers");
                        KafkaAgentStorage.KAFKA_BOOTSTRAP_CONSUMER_STRING =
                                Optional.ofNullable(bootstrapServers)
                                        .map(Object::toString)
                                        .orElse("");
                    }
                }
            } catch (Exception e) {
                System.err.println("ByteBuddyAgentKafkaInterceptor ERROR: " + e.getMessage());
            }
        }
    }


}
