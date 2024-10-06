package io.bitdive.java_agent.byte_buddy_agent;

import io.bitdive.java_agent.method_advice.RequestWebInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyAgentRequestWeb {
    public static void init()  {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(ElementMatchers.nameStartsWith("org.springframework.web.filter.RequestContextFilter"))
                .transform((builder, typeDescription, classLoader, module,sdf) ->
                        builder.method(ElementMatchers.named("initContextHolders"))
                                .intercept(Advice.to(RequestWebInterceptor.class))
                ).installOnByteBuddyAgent();
    }
}
