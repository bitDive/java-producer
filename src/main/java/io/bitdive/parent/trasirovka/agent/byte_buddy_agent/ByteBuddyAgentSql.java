package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.SQLUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static io.bitdive.parent.message_producer.MessageService.sendMessageSQL;

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

    public static class SqlInterceptor {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object stmt, @Advice.Origin Method method) {
            try {
                if (!ContextManager.isMessageIdQueueEmpty()) {
                    sendMessageSQL(ContextManager.getMessageIdQueueNew(),
                            ContextManager.getTraceId(),
                            ContextManager.getSpanId(),
                            SQLUtils.getSQLFromStatement(stmt)
                    );
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("error call executeQuery for  jdbc error: " + e.getMessage());
                }
            }
        }

    }
}
