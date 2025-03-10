package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.KafkaAgentStorage;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.bitdive.parent.message_producer.MessageService.sendMessageKafkaSend;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class ByteBuddyAgentKafkaSend {

    public static void init() {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(typeDescription ->
                        Objects.equals(typeDescription.getCanonicalName(), "org.apache.kafka.clients.producer.KafkaProducer"))
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder
                                .visit(Advice.to(SendAdviceKafka.class)
                                        .on(named("send")
                                                .and(takesArguments(2))
                                                .and(returns(named("java.util.concurrent.Future")))))
                )
                .installOnByteBuddyAgent();
    }

    public static class SendAdviceKafka {

        @Advice.OnMethodEnter
        public static MethodContext onEnter(
                @Advice.This Object producer,
                @Advice.Origin("#t.#m") String method,
                @Advice.AllArguments Object[] args) {
            MethodContext context = new MethodContext();
            if (args.length > 0) {
                context.flagMonitoring = true;
                context.spanId = ContextManager.getSpanId();
                context.traceId = ContextManager.getTraceId();
                context.UUIDMessage = UuidCreator.getTimeBased().toString();

                Object record = args[0];
                String topicName = "";
                String messageBody = "";
                String serviceName = "";

                try {
                    // Доступ к приватному полю producerConfig (название может отличаться в зависимости от версии)
                    Field configField = producer.getClass().getDeclaredField("producerConfig");
                    configField.setAccessible(true);
                    Object producerConfig = configField.get(producer);

                    // Из producerConfig получаем оригинальные настройки. Обычно имеется метод originals(), возвращающий Map.
                    Method originalsMethod = producerConfig.getClass().getMethod("originals");
                    Object originals = originalsMethod.invoke(producerConfig);

                    if (originals instanceof Map) {
                        Map<?, ?> configMap = (Map<?, ?>) originals;
                        Object bootstrapServers = configMap.get("bootstrap.servers");
                        serviceName = Optional.ofNullable(bootstrapServers)
                                .map(Object::toString)
                                .orElse("");
                        KafkaAgentStorage.KAFKA_BOOTSTRAP_PRODUCER_STRING = serviceName;
                    }
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Не удалось получить bootstrap.servers: " + e.getMessage());
                    }
                }

                try {
                    Method valueMethod = record.getClass().getMethod("value");
                    messageBody = Optional.ofNullable(valueMethod.invoke(record))
                            .map(ReflectionUtils::objectToString)
                            .orElse("");
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Не удалось получить значение сообщения: " + e.getMessage());
                    }
                }

                try {
                    Method topicMethod = record.getClass().getMethod("topic");
                    topicName = Optional.ofNullable(topicMethod.invoke(record)).map(Object::toString).orElse("");
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Не удалось получить значение топика: " + e.getMessage());
                    }
                }

                context.topicName = topicName;
                context.messageBody = messageBody;
                context.serviceName = serviceName;
                context.timestampStart = OffsetDateTime.now();
                context.parentMessageId = ContextManager.getMessageIdQueueNew();

            }
            return context;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(
                @Advice.Enter MethodContext context,
                @Advice.Thrown Throwable throwable) {
            if (context.flagMonitoring) {
                String errorMessage = "";

                if (throwable != null)
                    errorMessage = throwable.getMessage();
                sendMessageKafkaSend(
                        context.UUIDMessage,
                        context.spanId,
                        context.traceId,
                        context.topicName,
                        context.messageBody,
                        context.serviceName,
                        context.timestampStart,
                        OffsetDateTime.now(),
                        context.parentMessageId,
                        errorMessage
                );
            }
        }
    }


    public static class MethodContext {
        public boolean flagMonitoring = true;
        public String traceId;
        public String spanId;
        public String UUIDMessage;
        public String topicName;
        public String messageBody;
        public String serviceName;
        public String parentMessageId;
        public OffsetDateTime timestampStart;

    }

}
