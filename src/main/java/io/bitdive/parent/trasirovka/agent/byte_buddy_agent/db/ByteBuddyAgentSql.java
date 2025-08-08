package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import io.bitdive.parent.trasirovka.agent.utils.SQLUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.SQLUtils.getCallableStatement;

public class ByteBuddyAgentSql {

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(typeDescription ->
                        typeDescription.isAssignableTo(Statement.class) &&
                                !typeDescription.getName().contains("Proxy") &&
                                !typeDescription.getName().contains("Delegating") &&
                                !typeDescription.getName().startsWith("com.zaxxer.hikari") &&
                                !typeDescription.getName().contains("springframework")
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
                .installOn(instrumentation);
    }

    public static class SqlAdvice {

        @Advice.OnMethodEnter
        public static MethodContext onEnter(@Advice.This Object stmt,
                                            @Advice.Origin Method method,
                                            @Advice.AllArguments Object[] args) {

            MethodContext context = new MethodContext();
            if (LoggerStatusContent.getEnabledProfile()) return context;
            String sqlFromStatement = "";

            if (args.length == 1) {
                if (args[0] instanceof CallableStatement) {
                    sqlFromStatement = getCallableStatement(args[0]);
                } else {
                    sqlFromStatement = args[0].toString();
                }
            } else {
                sqlFromStatement = SQLUtils.getSQLFromStatement(stmt);
            }

            String connectionUrl = SQLUtils.getConnectionUrlFromStatement(stmt);

            context.flagNoMonitoring = sqlFromStatement == null || sqlFromStatement.isEmpty();

            context.traceId = ContextManager.getTraceId();
            context.spanId = ContextManager.getSpanId();
            context.UUIDMessage = UuidCreator.getTimeBased().toString();

            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {

                    sendMessageDBStart(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            sqlFromStatement,
                            connectionUrl,
                            OffsetDateTime.now(),
                            ContextManager.getMessageIdQueueNew(),
                            MessageTypeEnum.SQL_START
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
                                  @Advice.Return Object returnValue) throws SQLException {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {

                    sendMessageDBEnd(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            OffsetDateTime.now(),
                            getaNullThrowable(throwable),
                            MessageTypeEnum.SQL_END
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


    public static List<Map<String, Object>> readResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
