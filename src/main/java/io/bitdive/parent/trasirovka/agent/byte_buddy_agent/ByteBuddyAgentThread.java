package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.dto.TraceMethodContext;
import io.bitdive.parent.trasirovka.agent.utils.ContextCallableCustom;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;

public class ByteBuddyAgentThread {
    public static void init(Instrumentation instrumentation) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(ElementMatchers.nameStartsWith("org.springframework.util.concurrent.FutureUtils"))
                .transform((builder, typeDescription, classLoader, module, sdf) ->
                        builder.method(ElementMatchers.named("callAsync").and(ElementMatchers.takesArguments(2)))
                                .intercept(Advice.to(ThreadInterceptor.class))
                ).installOn(instrumentation);
    }

    public static class ThreadInterceptor {
        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.Origin String method,
                @Advice.Argument(value = 0, readOnly = false) Callable<?> callable) {

            TraceMethodContext currentContext = ContextManager.getContext();
            callable = new ContextCallableCustom<>(callable, currentContext);
        }
    }
}
