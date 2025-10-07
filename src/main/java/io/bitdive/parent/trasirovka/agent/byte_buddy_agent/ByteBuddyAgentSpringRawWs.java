package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.message_producer.MessageService;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;

import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class ByteBuddyAgentSpringRawWs {

    public static ResettableClassFileTransformer init(Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(hasSuperType(named("org.springframework.web.socket.handler.AbstractWebSocketHandler")))
                .transform((builder, td, cl, module, pd) -> builder
                        .method(named("handleTextMessage")
                                .and(takesArguments(2))
                                .and(takesArgument(0, named("org.springframework.web.socket.WebSocketSession")))
                                .and(takesArgument(1, named("org.springframework.web.socket.TextMessage"))))
                        .intercept(MethodDelegation.to(TextInterceptor.class))
                )
                .installOn(inst);
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
                    Object uri = session.getClass().getMethod("getUri").invoke(session);
                    destination = uri != null ? uri.toString() : "";
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
                        getaNullThrowable(thrown),
                        true,
                        ContextManager.getMethodInpointName(),
                        ContextManager.getMessageInpointId(),
                        ContextManager.getClassInpointName()
                );
            }
            return retVal;
        }
    }
}
