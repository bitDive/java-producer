package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;



import io.bitdive.parent.trasirovka.agent.method_advice.ThreadCreatorInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyAgentThreadCreator {
    public static void init()  {
        new AgentBuilder.Default()
                .type(ElementMatchers.nameStartsWith("org.springframework.util.CustomizableThreadCreator"))  // Укажите пакет ваших классов
                .transform((builder, typeDescription, classLoader, module, sd) ->
                        builder.method(ElementMatchers.named("createThread"))  // Выбор всех методов для обертки
                                .intercept(Advice.to(ThreadCreatorInterceptor.class))
                ).installOnByteBuddyAgent();
    }
}
