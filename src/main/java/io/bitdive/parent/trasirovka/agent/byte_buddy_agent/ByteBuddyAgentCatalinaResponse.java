package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.Optional;

import static io.bitdive.parent.message_producer.MessageService.sendMessageWebResponse;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public class ByteBuddyAgentCatalinaResponse {

    /**
     * View injected into {@code org.apache.catalina.connector.Request} to avoid reflection in advice.
     */
    public interface BitDiveTomcatRequestView {
        int bitdiveResponseStatus();
    }

    public static AgentBuilder  init(AgentBuilder agentBuilder) {
        return agentBuilder
                .type(ElementMatchers.named("org.apache.catalina.connector.Request"))
                .transform((builder, typeDescription, classLoader, module, dd) -> {

                    MethodDescription.InDefinedShape mGetResponse =
                            findMethodInHierarchy(typeDescription, "getResponse", 0);
                    if (mGetResponse == null) {
                        return builder.visit(Advice.to(CatalinaResponseInterceptor.class)
                                .on(ElementMatchers.named("finishRequest")));
                    }

                    TypeDescription respType = mGetResponse.getReturnType().asErasure();
                    MethodDescription.InDefinedShape mGetStatus =
                            findMethodInHierarchy(respType, "getStatus", 0);
                    if (mGetStatus == null) {
                        return builder.visit(Advice.to(CatalinaResponseInterceptor.class)
                                .on(ElementMatchers.named("finishRequest")));
                    }

                    return builder
                            .implement(BitDiveTomcatRequestView.class)
                            .defineMethod("bitdiveResponseStatus", int.class, Visibility.PUBLIC)
                            .intercept(
                                    MethodCall.invoke(mGetStatus)
                                            .onMethodCall(MethodCall.invoke(mGetResponse))
                            )
                            .visit(Advice.to(CatalinaResponseInterceptor.class)
                                    .on(ElementMatchers.named("finishRequest")));
                });
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

                int status = 0;
                if (responseObj instanceof BitDiveTomcatRequestView) {
                    status = ((BitDiveTomcatRequestView) responseObj).bitdiveResponseStatus();
                }

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

    // =========================
    // ByteBuddy helpers
    // =========================

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type, String name, int argsCount) {
        TypeDescription cur = type;
        while (cur != null) {
            try {
                MethodList<MethodDescription.InDefinedShape> declared =
                        cur.getDeclaredMethods().filter(named(name).and(takesArguments(argsCount)));
                if (!declared.isEmpty()) return declared.getOnly();
            } catch (Exception ignored) {
            }

            // interfaces
            try {
                for (TypeDescription.Generic itf : cur.getInterfaces()) {
                    MethodDescription.InDefinedShape m = findMethodInHierarchy(itf.asErasure(), name, argsCount);
                    if (m != null) return m;
                }
            } catch (Exception ignored) {
            }

            TypeDescription.Generic sc = cur.getSuperClass();
            cur = (sc == null) ? null : sc.asErasure();
        }
        return null;
    }

}
