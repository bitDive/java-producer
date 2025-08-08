package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum.NEO4J_DB_END;
import static io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum.NEO4J_DB_START;

public class ByteBuddyAgentNeo4j {

    public static ResettableClassFileTransformer init(Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.neo4j.driver.internal.AbstractQueryRunner")))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(Neo4jAdvice.class)
                                .on(ElementMatchers.named("run")
                                        .and(ElementMatchers.takesArguments(1))
                                )
                        )
                )
                .installOn(inst);
    }

    public static class Neo4jAdvice {

        public static class MethodContext {
            public String statement;
            public Map<String, Object> parameters;
            public String traceId;
            public String spanId;
            public String uuid;
            public boolean skip;
            public String connectionUrl;
        }

        @Advice.OnMethodEnter
        public static MethodContext onEnter(@Advice.This Object session,
                                            @Advice.Origin Method method,
                                            @Advice.AllArguments Object[] args) throws Exception {
            MethodContext ctx = new MethodContext();
            if (LoggerStatusContent.getEnabledProfile()) return ctx;
            ctx.statement = "";
            ctx.parameters = Collections.emptyMap();
            extractedParametrs(args, ctx);

            extractedSessionInfo(session, ctx);

            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();
            ctx.uuid = UuidCreator.getTimeBased().toString();
            ctx.skip = ctx.statement == null || ctx.statement.isEmpty();

            ctx.statement = injectParams(ctx.statement, ctx.parameters);

            if (!ctx.skip && !ContextManager.isMessageIdQueueEmpty()) {
                try {
                    sendMessageDBStart(
                            ctx.uuid,
                            ctx.traceId,
                            ctx.spanId,
                            ctx.statement,
                            "neo4j://" + ctx.connectionUrl,
                            OffsetDateTime.now(),
                            ContextManager.getMessageIdQueueNew(),
                            NEO4J_DB_START
                    );
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Neo4j START error: " + e.getMessage());
                    }
                }
            }

            return ctx;
        }

        public static void extractedSessionInfo(Object session, MethodContext ctx) {
            try {
                String cls = session.getClass().getSimpleName();
                if ("InternalSession".equals(cls)) {
                    Object sessionFactory = ReflectionUtils.getFieldValue(session, "sessionFactory");
                    Object driverInternal = ReflectionUtils.getFieldValue(sessionFactory, "driver");
                    Object address = ReflectionUtils.invokeMethod(driverInternal, "getServerAddress");
                    ctx.connectionUrl = address != null ? address.toString() : "neo4j";
                } else if ("InternalTransaction".equals(cls)) {
                    Object tx = ReflectionUtils.getFieldValue(session, "tx");
                    Object connection = ReflectionUtils.invokeMethod(tx, "connection");
                    Object address;
                    try {
                        address = ReflectionUtils.invokeMethod(connection, "getServerAddress");
                    } catch (NoSuchMethodException ex) {
                        address = ReflectionUtils.invokeMethod(connection, "serverAddress");
                    }
                    ctx.connectionUrl = address != null ? address.toString() : "neo4j";
                } else {
                    ctx.connectionUrl = "neo4j";
                }
            } catch (Exception e) {
                ctx.connectionUrl = "neo4j";
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Не удалось получить Neo4j URL: " + e.getMessage());
                }
            }
        }

        public static void extractedParametrs(Object[] args, MethodContext ctx) throws Exception {
            if (args.length > 0 && args[0] != null) {
                Object first = args[0];
                String className = first.getClass().getName();

                if ("org.neo4j.driver.Query".equals(className)) {
                    ctx.statement = (String) ReflectionUtils.invokeMethod(first, "text");
                    Object paramsObj = ReflectionUtils.invokeMethod(first, "parameters");
                    if (paramsObj != null) {
                        try {
                            ctx.parameters = (Map<String, Object>) ReflectionUtils.invokeMethod(paramsObj, "asMap");
                        } catch (Exception ex) {
                            if (paramsObj instanceof Map) {
                                ctx.parameters = (Map<String, Object>) paramsObj;
                            }
                        }
                    }
                } else if (first instanceof String) {
                    ctx.statement = (String) first;
                    if (args.length > 1 && args[1] instanceof Map) {
                        ctx.parameters = (Map<String, Object>) args[1];
                    }
                } else {
                    ctx.statement = first.toString();
                }
            }
        }

        public static String injectParams(String query, Map<String, Object> params) {
            if (query == null || params == null || params.isEmpty()) {
                return query;
            }
            String result = query;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String name = e.getKey();
                Object v = e.getValue();
                String lit;
                if (v == null) {
                    lit = "null";
                } else if (v instanceof String) {
                    String s = ((String) v).replace("'", "\\'");
                    lit = "'" + s + "'";
                } else {
                    lit = v.toString();
                }
                result = result.replace("$" + name, lit);
            }
            return result;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodContext ctx,
                                  @Advice.Thrown Throwable t) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            if (!ctx.skip && !ContextManager.isMessageIdQueueEmpty()) {
                try {
                    sendMessageDBEnd(
                            ctx.uuid,
                            ctx.traceId,
                            ctx.spanId,
                            OffsetDateTime.now(),
                            getaNullThrowable(t),
                            NEO4J_DB_END
                    );
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Neo4j END error: " + e.getMessage());
                    }
                }
            }
        }
    }
}
