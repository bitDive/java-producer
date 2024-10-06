package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;


import io.bitdive.parent.trasirovka.agent.method_advice.ThreadInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyAgentThread {
    public static void init()  {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(ElementMatchers.nameStartsWith("org.springframework.util.concurrent.FutureUtils"))
                .transform((builder, typeDescription, classLoader, module,sdf) ->
                        builder.method(ElementMatchers.named("callAsync").and(ElementMatchers.takesArguments(2)))
                                .intercept(Advice.to(ThreadInterceptor.class))
                ).installOnByteBuddyAgent();
    }
}
