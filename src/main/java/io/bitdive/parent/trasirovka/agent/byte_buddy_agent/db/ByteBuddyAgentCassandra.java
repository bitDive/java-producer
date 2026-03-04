package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;

public class ByteBuddyAgentCassandra {

    /**
     * Injected into BoundStatement implementations to avoid reflection.
     */
    public interface BitDiveCassandraBoundStatementView {
        String bitdiveQueryString();
        Object bitdiveValuesObj();
    }

    /**
     * Injected into CqlSession implementations to avoid reflection.
     */
    public interface BitDiveCassandraSessionView {
        Object bitdiveMetadata();
    }

    /**
     * Injected into Metadata implementations to avoid reflection.
     */
    public interface BitDiveCassandraMetadataView {
        Object bitdiveNodes();
    }

    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        try {
            Class<?> clientClass = Class.forName("com.datastax.oss.driver.api.core.CqlSession");
            Class<?> boundStmtClass = Class.forName("com.datastax.oss.driver.api.core.cql.BoundStatement");
            Class<?> metadataClass = Class.forName("com.datastax.oss.driver.api.core.metadata.Metadata");
            return agentBuilder
                    // BoundStatement view
                    .type(td -> td.isAssignableTo(boundStmtClass)
                            && !td.isInterface()
                            && !td.getName().contains("Proxy")
                            && !td.getName().contains("Delegating"))
                    .transform((builder, td, cl, module, dd) -> {
                        try {
                            // query: getPreparedStatement().getQuery().toString()
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetPrepared =
                                    td.getDeclaredMethods().filter(ElementMatchers.named("getPreparedStatement").and(ElementMatchers.takesArguments(0))).getOnly();
                            net.bytebuddy.description.type.TypeDescription psType = mGetPrepared.getReturnType().asErasure();
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetQuery =
                                    psType.getDeclaredMethods().filter(ElementMatchers.named("getQuery").and(ElementMatchers.takesArguments(0))).getOnly();
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mToString =
                                    new net.bytebuddy.description.method.MethodDescription.ForLoadedMethod(Object.class.getMethod("toString"));

                            builder = builder
                                    .implement(BitDiveCassandraBoundStatementView.class)
                                    .defineMethod("bitdiveQueryString", String.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                    .intercept(
                                            net.bytebuddy.implementation.MethodCall.invoke(mToString)
                                                    .onMethodCall(
                                                            net.bytebuddy.implementation.MethodCall.invoke(mGetQuery)
                                                                    .onMethodCall(net.bytebuddy.implementation.MethodCall.invoke(mGetPrepared))
                                                    )
                                    );
                        } catch (Exception ignored) {
                            // fall back later
                        }

                        // values: prefer getValues(), else field "values" if present
                        try {
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetValues =
                                    td.getDeclaredMethods().filter(ElementMatchers.named("getValues").and(ElementMatchers.takesArguments(0))).getOnly();
                            builder = builder
                                    .implement(BitDiveCassandraBoundStatementView.class)
                                    .defineMethod("bitdiveValuesObj", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                    .intercept(net.bytebuddy.implementation.MethodCall.invoke(mGetValues));
                        } catch (Exception ignored) {
                            try {
                                // only if field exists
                                td.getDeclaredFields().filter(ElementMatchers.named("values")).getOnly();
                                builder = builder
                                        .implement(BitDiveCassandraBoundStatementView.class)
                                        .defineMethod("bitdiveValuesObj", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                        .intercept(net.bytebuddy.implementation.FieldAccessor.ofField("values"));
                            } catch (Exception ignored2) {
                            }
                        }

                        return builder;
                    })

                    // Metadata view
                    .type(td -> td.isAssignableTo(metadataClass) && !td.isInterface())
                    .transform((builder, td, cl, module, dd) -> {
                        try {
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetNodes =
                                    td.getDeclaredMethods().filter(ElementMatchers.named("getNodes").and(ElementMatchers.takesArguments(0))).getOnly();
                            return builder
                                    .implement(BitDiveCassandraMetadataView.class)
                                    .defineMethod("bitdiveNodes", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                    .intercept(net.bytebuddy.implementation.MethodCall.invoke(mGetNodes));
                        } catch (Exception e) {
                            return builder;
                        }
                    })

                    // CqlSession view
                    .type(typeDescription ->
                            typeDescription.isAssignableTo(clientClass)
                                    && !typeDescription.getName().contains("Proxy")
                                    && !typeDescription.getName().contains("Delegating")
                    )
                    .transform((builder, typeDescription, classLoader, module, dd) -> {
                        try {
                            net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetMetadata =
                                    typeDescription.getDeclaredMethods()
                                            .filter(ElementMatchers.named("getMetadata").and(ElementMatchers.takesArguments(0)))
                                            .getOnly();
                            builder = builder
                                    .implement(BitDiveCassandraSessionView.class)
                                    .defineMethod("bitdiveMetadata", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                    .intercept(net.bytebuddy.implementation.MethodCall.invoke(mGetMetadata));
                        } catch (Exception ignored) {
                        }

                        return builder.visit(Advice.to(CassandraAdvice.class)
                                .on(ElementMatchers.named("execute")
                                        .and(ElementMatchers.not(ElementMatchers.nameContains("Internal")))));
                    });
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Class com.datastax.oss.driver.api.core.CqlSession not found in ClassLoader.");
            }
        }
        return agentBuilder;
    }

    public static class CassandraAdvice {

        @Advice.OnMethodEnter
        public static MethodContext onEnter(@Advice.This Object session,
                                            @Advice.Origin Method method,
                                            @Advice.AllArguments Object[] args) {
            MethodContext context = new MethodContext();

            if (LoggerStatusContent.getEnabledProfile()) return context;

            if (args == null || args.length == 0 ||
                    !args[0].getClass().getSimpleName().contains("BoundStatement")) {
                context.flagNoMonitoring = true;
                return context;
            }

            String query = extractQuery(args[0]);
            String connectionInfo = "cassandra//:" + extractConnectionAddress(session);

            context.flagNoMonitoring = query == null || query.isEmpty();
            context.traceId = ContextManager.getTraceId();
            context.spanId = ContextManager.getSpanId();
            context.UUIDMessage = UuidCreator.getTimeBased().toString();

            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {
                    sendMessageDBStart(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            query,
                            connectionInfo,
                            OffsetDateTime.now(),
                            ContextManager.getMessageIdQueueNew(),
                            MessageTypeEnum.CASSANDRA_DB_START
                    );
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending Cassandra query start message: " + e.getMessage());
                }
            }
            return context;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter MethodContext context,
                                  @Advice.Thrown Throwable throwable,
                                  @Advice.Return Object returnValue) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                if (!context.flagNoMonitoring && !ContextManager.isMessageIdQueueEmpty()) {
                    sendMessageDBEnd(
                            context.UUIDMessage,
                            context.traceId,
                            context.spanId,
                            OffsetDateTime.now(),
                            ReflectionUtils.objectToString(throwable),
                            MessageTypeEnum.CASSANDRA_DB_END
                    );
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending Cassandra query completion message: " + e.getMessage());
                }
            }
        }

        public static String extractQuery(Object request) {
            String query = "";
            if (request instanceof BitDiveCassandraBoundStatementView) {
                try {
                    query = ((BitDiveCassandraBoundStatementView) request).bitdiveQueryString();
                } catch (Exception ignored) {
                }
            }
            if (query == null || query.isEmpty()) {
                query = request.toString();
            }
            if (request.getClass().getSimpleName().contains("BoundStatement")) {
                Object[] params = extractParameters(request);
                if (params != null && params.length > 0) {
                    query = fillQueryParameters(query, params);
                }
            }
            return query;
        }

        private static Object[] extractParameters(Object request) {
            Object valuesObj = null;
            if (request instanceof BitDiveCassandraBoundStatementView) {
                try {
                    valuesObj = ((BitDiveCassandraBoundStatementView) request).bitdiveValuesObj();
                } catch (Exception ignored) {
                }
            }
            if (valuesObj != null) {
                if (valuesObj instanceof List) {
                    List<?> list = (List<?>) valuesObj;
                    return list.toArray();
                } else if (valuesObj.getClass().isArray()) {
                    return (Object[]) valuesObj;
                }
            }
            return null;
        }

        private static String fillQueryParameters(String query, Object[] params) {
            for (Object param : params) {
                String value = (param == null) ? "null" : paramToString(param);
                query = query.replaceFirst("\\?", value);
            }
            return query;
        }


        private static String paramToString(Object param) {
            if (param == null) {
                return "null";
            }
            if (param instanceof String) {
                return "'" + param.toString() + "'";
            }
            if (param instanceof ByteBuffer) {
                ByteBuffer buffer = ((ByteBuffer) param).duplicate();
                int len = buffer.remaining();
                byte[] bytes = new byte[len];
                buffer.get(bytes);
                if (len == 16) {
                    ByteBuffer bb = ByteBuffer.wrap(bytes);
                    long mostSigBits = bb.getLong();
                    long leastSigBits = bb.getLong();
                    UUID uuid = new UUID(mostSigBits, leastSigBits);
                    return uuid.toString();
                }
                if (len == 4) {
                    ByteBuffer bb = ByteBuffer.wrap(bytes);
                    return String.valueOf(bb.getInt());
                }
                boolean isText = true;
                for (byte b : bytes) {
                    if (b < 32 || b > 126) {
                        isText = false;
                        break;
                    }
                }
                if (isText) {
                    return "'" + new String(bytes, StandardCharsets.UTF_8) + "'";
                }
                return "0x" + bytesToHex(bytes);
            }
            return param.toString();
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }

        public static String extractConnectionAddress(Object session) {
            try {
                if (session instanceof BitDiveCassandraSessionView) {
                    Object metadata = ((BitDiveCassandraSessionView) session).bitdiveMetadata();
                    if (metadata instanceof BitDiveCassandraMetadataView) {
                        Object nodesObj = ((BitDiveCassandraMetadataView) metadata).bitdiveNodes();
                        if (nodesObj instanceof Map) {
                            Map<?, ?> nodes = (Map<?, ?>) nodesObj;
                            if (!nodes.isEmpty()) {
                                Object firstNode = nodes.values().iterator().next();
                                if (firstNode != null) {
                                    // Best-effort without reflection: node.toString() usually contains endpoint/address
                                    return firstNode.toString();
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error retrieving connection address: " + e.getMessage());
                }
            }
            return "CassandraSession";
        }

        public static class MethodContext {
            public boolean flagNoMonitoring;
            public String traceId;
            public String spanId;
            public String UUIDMessage;
        }
    }
}
