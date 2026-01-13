package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.util.Optional;

import static io.bitdive.parent.message_producer.MessageService.sendMessageWebResponse;

public class ByteBuddyAgentCatalinaResponse {
    public static AgentBuilder  init(AgentBuilder agentBuilder) {
        return agentBuilder
                .type(ElementMatchers.named("org.apache.catalina.connector.Request"))
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder.visit(Advice.to(CatalinaResponseInterceptor.class)
                                .on(ElementMatchers.named("finishRequest")))
                );
    }

    public static class CatalinaResponseInterceptor {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object responseObj) {
            // ВАЖНО: очистка ThreadLocal должна происходить ВСЕГДА (в т.ч. при раннем выходе и исключениях)
            try {
                if (LoggerStatusContent.getEnabledProfile()) {
                    return;
                }

                // Не шлем сообщения для actuator, но контекст/буферы все равно нужно чистить в finally
                if (Optional.of(ContextManager.getUrlStart()).orElse("").toLowerCase().contains("/actuator/")) {
                    return;
                }

                Class<?> requestClass = responseObj.getClass();

                Method getResponseMethod = requestClass.getMethod("getResponse");
                Object responseInternal = getResponseMethod.invoke(responseObj);

                Class<?> responseClass = responseInternal.getClass();
                Method getStatusMethod = responseClass.getMethod("getStatus");
                int status = (int) getStatusMethod.invoke(responseInternal);

                // finalize collected request body into context
                try {
                    byte[] bodyBytes = io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector.getBytes();
                    if (bodyBytes != null) {
                        ContextManager.setRequestBodyBytes(bodyBytes);
                    }
                } catch (Exception ignored) {
                }

                sendMessageWebResponse(
                        ContextManager.getMessageStart(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        status
                );
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("error call finishRequest for org.apache.catalina.connector.Request  error : " + e.getMessage());
                }
            } finally {
                // КРИТИЧНО: Очистка всех ThreadLocal переменных после завершения запроса
                ContextManager.cleanupSafely();
                io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector.cleanupSafely();
            }
        }
    }

}
