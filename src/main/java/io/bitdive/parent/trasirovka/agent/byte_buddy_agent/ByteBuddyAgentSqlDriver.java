package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Driver;
import java.util.concurrent.Callable;

import static io.bitdive.parent.message_producer.MessageService.sendMessageCriticalDBError;

public class ByteBuddyAgentSqlDriver {

    public static void init() {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.isSubTypeOf(Driver.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(ElementMatchers.named("connect"))
                                .intercept(MethodDelegation.to(DriverInterceptor.class))
                )
                .installOnByteBuddyAgent();
    }

    public static class DriverInterceptor {

        @RuntimeType
        public static Object intercept(@Origin Method method,
                                       @SuperCall Callable<?> zuper,
                                       @This Object thiz,
                                       @AllArguments Object[] args) throws Throwable {
            String url = (args[0] instanceof String) ? (String) args[0] : null;
            // Properties info = (args[1] instanceof Properties) ? (Properties) args[1] : null;

            try {
                return zuper.call();
            } catch (InvocationTargetException t) {
                sendMessageCriticalDBError(url, t.getCause().getMessage());
                throw t.getCause();
            }
        }
    }
}
