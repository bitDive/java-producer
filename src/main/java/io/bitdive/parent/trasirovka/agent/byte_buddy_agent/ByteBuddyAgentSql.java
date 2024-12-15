package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.SQLUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;

import static io.bitdive.parent.message_producer.MessageService.*;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;

public class ByteBuddyAgentSql {

    public static void init() {
        new AgentBuilder.Default()
                .type(
                        ElementMatchers.isSubTypeOf(PreparedStatement.class)
                                .or(ElementMatchers.isSubTypeOf(Statement.class))
                                .and(ElementMatchers.nameContains("jdbc"))
                )
                .transform((builder, typeDescription, classLoader, module, dd) ->
                        builder.visit(Advice.to(SqlAdvice.class)
                                .on(ElementMatchers.named("executeQuery")
                                        .or(ElementMatchers.named("executeUpdate"))
                                        .or(ElementMatchers.named("execute"))
                                        .and(ElementMatchers.not(ElementMatchers.nameContains("Internal")))
                                )
                        )
                )
                .installOnByteBuddyAgent();
    }


    public static class SqlAdvice {

        @Advice.OnMethodEnter
        public static MethodContext onEnter(@Advice.This Object stmt,
                                            @Advice.Origin Method method) {
            MethodContext context = new MethodContext();

            String sqlFromStatement = SQLUtils.getSQLFromStatement(stmt);
            context.flagNoMonitoring = sqlFromStatement == null || sqlFromStatement.isEmpty();

            context.traceId = ContextManager.getTraceId();
            context.spanId = ContextManager.getSpanId();
            context.UUIDMessage = UuidCreator.getTimeBased().toString();


            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {
                    sendMessageSQLStart(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            sqlFromStatement,
                            OffsetDateTime.now(),
                            ContextManager.getMessageIdQueueNew()
                    );
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending SQL start message: " + e.getMessage());
                }
            }

            return context;
        }


        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodContext context,
                                  @Advice.Thrown Throwable throwable,
                                  @Advice.Return Object returnValue) {
            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {
                    sendMessageSQLEnd(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            OffsetDateTime.now(),
                            getaNullThrowable(throwable)
                    );
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending SQL end message: " + e.getMessage());
                }
            }
        }

        public static class MethodContext {
            public boolean flagNoMonitoring;
            public String traceId;
            public String spanId;
            public String UUIDMessage;
        }
    }
}
