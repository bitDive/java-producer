package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Optional;

import static io.bitdive.parent.message_producer.MessageService.sendMessageWebResponse;

public class ByteBuddyAgentCatalinaResponse {
    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .type(ElementMatchers.named("org.apache.catalina.connector.Request"))
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder.visit(Advice.to(CatalinaResponseInterceptor.class)
                                .on(ElementMatchers.named("finishRequest")))
                )
                .installOn(instrumentation);
    }

    public static class CatalinaResponseInterceptor {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object responseObj) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                if (Optional.of(ContextManager.getUrlStart()).orElse("").toLowerCase().contains("/actuator/")) return;

                Class<?> requestClass = responseObj.getClass();

                Method getResponseMethod = requestClass.getMethod("getResponse");
                Object responseInternal = getResponseMethod.invoke(responseObj);

                Class<?> responseClass = responseInternal.getClass();
                Method getStatusMethod = responseClass.getMethod("getStatus");
                int status = (int) getStatusMethod.invoke(responseInternal);

                sendMessageWebResponse(
                        ContextManager.getMessageStart(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        status
                );
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("error call finishRequest for org.apache.catalina.connector.Request  error : " + e.getMessage());

            }
        }
    }

}
