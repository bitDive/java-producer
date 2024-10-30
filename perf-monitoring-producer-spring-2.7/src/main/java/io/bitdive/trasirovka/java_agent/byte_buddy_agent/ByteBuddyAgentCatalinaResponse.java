package io.bitdive.trasirovka.java_agent.byte_buddy_agent;

import io.bitdive.trasirovka.java_agent.method_advice.CatalinaResponseInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyAgentCatalinaResponse {
    public static void init() {
        new AgentBuilder.Default()
                .type(ElementMatchers.named("org.apache.catalina.connector.Response"))
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder.visit(Advice.to(CatalinaResponseInterceptor.class)
                                .on(ElementMatchers.named("finishResponse"))))
                .installOnByteBuddyAgent();
    }

}
