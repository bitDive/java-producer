package io.bitdive.trasirovka.java_agent.byte_buddy_agent;


import io.bitdive.trasirovka.java_agent.method_advice.ResponseWebInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyAgentResponseWeb {
    public static void init() {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .type(ElementMatchers.nameStartsWith("org.springframework.http.client.AbstractClientHttpRequest"))
                .transform((builder, typeDescription, classLoader, module,sdf) ->
                        builder.method(ElementMatchers.named("execute"))
                                .intercept(Advice.to(ResponseWebInterceptor.class))
                ).installOnByteBuddyAgent();
    }
}
