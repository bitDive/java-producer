package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.ContextRunnableCustom;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

public class ByteBuddyAgentThreadCreator {
    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.nameStartsWith("org.springframework.util.CustomizableThreadCreator"))  // Укажите пакет ваших классов
                .transform((builder, typeDescription, classLoader, module, sd) ->
                        builder.method(ElementMatchers.named("createThread"))  // Выбор всех методов для обертки
                                .intercept(Advice.to(ThreadCreatorInterceptor.class))
                ).installOn(instrumentation);
    }

    public static class ThreadCreatorInterceptor {
        @Advice.OnMethodEnter
        public static void onMethodEnter(@Advice.Origin Method method,
                                         @Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
            runnable = new ContextRunnableCustom(runnable, ContextManager.getContext());
        }
    }
}
