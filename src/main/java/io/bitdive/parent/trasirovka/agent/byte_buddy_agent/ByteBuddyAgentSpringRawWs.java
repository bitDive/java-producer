package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.message_producer.MessageService;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class ByteBuddyAgentSpringRawWs {

    /**
     * View injected into {@code org.springframework.web.socket.WebSocketSession} implementations.
     * Avoids reflection in interceptor while keeping "no Spring dependency" approach.
     */
    public interface BitDiveWsSessionView {
        String bitdiveUriString();
    }

    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        return agentBuilder
                // session view (Spring WS)
                .type(hasSuperType(named("org.springframework.web.socket.WebSocketSession"))
                        .and(not(isInterface())))
                .transform((builder, td, cl, module, pd) -> {
                    MethodDescription.InDefinedShape mGetUri = findMethodInHierarchy(td, "getUri", 0);
                    if (mGetUri == null) return builder;

                    MethodDescription.InDefinedShape mToString;
                    try {
                        mToString = new MethodDescription.ForLoadedMethod(Object.class.getMethod("toString"));
                    } catch (NoSuchMethodException e) {
                        return builder;
                    }

                    return builder
                            .implement(BitDiveWsSessionView.class)
                            .defineMethod("bitdiveUriString", String.class, Visibility.PUBLIC)
                            .intercept(
                                    MethodCall.invoke(mToString)
                                            .onMethodCall(MethodCall.invoke(mGetUri))
                            );
                })
                .type(hasSuperType(named("org.springframework.web.socket.handler.AbstractWebSocketHandler")))
                .transform((builder, td, cl, module, pd) -> builder
                        .method(named("handleTextMessage")
                                .and(takesArguments(2))
                                .and(takesArgument(0, named("org.springframework.web.socket.WebSocketSession")))
                                .and(takesArgument(1, named("org.springframework.web.socket.TextMessage"))))
                        .intercept(MethodDelegation.to(TextInterceptor.class))
                );
    }

    public static class TextInterceptor {
        @RuntimeType
        public static Object intercept(@SuperCall Callable<?> zuper,
                                       @AllArguments Object[] args,
                                       @Origin Method method) throws Exception {
            if (LoggerStatusContent.getEnabledProfile()) {
                return zuper.call();
            }

            ContextManager.createNewRequest();
            String uuidMessage = UuidCreator.getTimeBased().toString();
            OffsetDateTime startTime = OffsetDateTime.now();
            Throwable thrown = null;

            ContextManager.setMethodCallContextQueue(uuidMessage);

            Object retVal;
            try {
                retVal = zuper.call();
            } catch (Throwable t) {
                thrown = t;
                throw t;
            } finally {
                String destination = "";
                try {
                    Object session = args[0];
                    if (session instanceof BitDiveWsSessionView) {
                        destination = ((BitDiveWsSessionView) session).bitdiveUriString();
                    }
                } catch (Throwable ignore) {
                }

                String argsString = ReflectionUtils.objectToString(paramConvert(args, method));
                ContextManager.setMessageInpointId(uuidMessage);
                ContextManager.setMethodCallContextQueue(method.getName());
                MessageService.sendMessageRawWsConsumer(
                        uuidMessage,
                        method.getDeclaringClass().getName(),
                        method.getName(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        startTime,
                        OffsetDateTime.now(),
                        destination,
                        argsString,
                        ReflectionUtils.objectToString(thrown),
                        true,
                        ContextManager.getMethodInpointName(),
                        ContextManager.getMessageInpointId(),
                        ContextManager.getClassInpointName()
                );
            }
            return retVal;
        }
    }

    // =========================
    // ByteBuddy helpers
    // =========================

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type, String name, int argsCount) {
        return findMethodInHierarchy(type, name, argsCount, new HashSet<String>());
    }

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type,
                                                                         String name,
                                                                         int argsCount,
                                                                         Set<String> visited) {
        if (type == null) return null;
        String typeName;
        try {
            typeName = type.getName();
        } catch (Exception e) {
            typeName = null;
        }
        if (typeName != null && !visited.add(typeName)) {
            return null;
        }

        try {
            MethodList<MethodDescription.InDefinedShape> declared =
                    type.getDeclaredMethods().filter(named(name).and(takesArguments(argsCount)));
            if (!declared.isEmpty()) return declared.getOnly();
        } catch (Exception ignored) {
        }

        try {
            for (TypeDescription.Generic itf : type.getInterfaces()) {
                MethodDescription.InDefinedShape m = findMethodInHierarchy(itf.asErasure(), name, argsCount, visited);
                if (m != null) return m;
            }
        } catch (Exception ignored) {
        }

        try {
            TypeDescription.Generic sc = type.getSuperClass();
            return (sc == null) ? null : findMethodInHierarchy(sc.asErasure(), name, argsCount, visited);
        } catch (Exception ignored) {
            return null;
        }
    }
}
