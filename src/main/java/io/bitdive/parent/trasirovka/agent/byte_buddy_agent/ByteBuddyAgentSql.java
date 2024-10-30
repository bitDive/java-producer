package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.method_advice.SqlInterceptor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.sql.PreparedStatement;
import java.sql.Statement;

public class ByteBuddyAgentSql {

    public static void init() {
        new AgentBuilder.Default()
                .type(ElementMatchers.isSubTypeOf(PreparedStatement.class)
                        .or(ElementMatchers.isSubTypeOf(Statement.class)).and(ElementMatchers.nameContains("jdbc"))
                )
                .transform((builder, typeDescription, classLoader, module, dd) -> builder
                        .method(ElementMatchers.named("executeQuery"))
                        .intercept(Advice.to(SqlInterceptor.class))
                )
                .installOnByteBuddyAgent();
    }
}
