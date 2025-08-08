package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;

public class ByteBuddyAgentCassandra {

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        try {
            Class<?> clientClass = Class.forName("com.datastax.oss.driver.api.core.CqlSession");
            return new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(typeDescription ->
                            typeDescription.isAssignableTo(clientClass)
                                    && !typeDescription.getName().contains("Proxy")
                                    && !typeDescription.getName().contains("Delegating")
                    )
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.visit(Advice.to(CassandraAdvice.class)
                                    .on(ElementMatchers.named("execute")
                                            .and(ElementMatchers.not(ElementMatchers.nameContains("Internal")))
                                    )
                            )
                    )
                    .installOn(instrumentation);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Class com.datastax.oss.driver.api.core.CqlSession not found in ClassLoader.");
            }
        }
        return null;
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
                            getaNullThrowable(throwable),
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
            try {
                Method psMethod = request.getClass().getMethod("getPreparedStatement");
                Object preparedStatement = psMethod.invoke(request);
                if (preparedStatement != null) {
                    try {
                        Method queryMethod = preparedStatement.getClass().getMethod("getQuery");
                        Object queryObj = queryMethod.invoke(preparedStatement);
                        if (queryObj != null) {
                            query = queryObj.toString();
                        }
                    } catch (Exception e) {
                    }
                }
            } catch (Exception ex) {
            }
            if (query.isEmpty()) {
                query = extractQueryWithReflection(request);
            }
            if (request.getClass().getSimpleName().contains("BoundStatement")) {
                Object[] params = extractParameters(request);
                if (params != null && params.length > 0) {
                    query = fillQueryParameters(query, params);
                }
            }
            return query;
        }

        private static String extractQueryWithReflection(Object request) {
            String query = "";
            try {
                Field field = request.getClass().getDeclaredField("query");
                field.setAccessible(true);
                Object value = field.get(request);
                if (value != null) {
                    query = value.toString();
                }
            } catch (NoSuchFieldException nsfe) {
                try {
                    Method method = request.getClass().getMethod("getQuery");
                    Object result = method.invoke(request);
                    if (result != null) {
                        query = result.toString();
                    }
                } catch (Exception e) {
                    query = request.toString();
                }
            } catch (Exception e) {
                query = request.toString();
            }
            return query;
        }

        private static Object[] extractParameters(Object request) {
            Object valuesObj = null;
            try {
                Method getValuesMethod = request.getClass().getMethod("getValues");
                valuesObj = getValuesMethod.invoke(request);
            } catch (Exception e) {
                try {
                    Field valuesField = request.getClass().getDeclaredField("values");
                    valuesField.setAccessible(true);
                    valuesObj = valuesField.get(request);
                } catch (Exception ex) {
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
                Method getMetadata = session.getClass().getMethod("getMetadata");
                Object metadata = getMetadata.invoke(session);
                if (metadata != null) {
                    Method getNodes = metadata.getClass().getMethod("getNodes");
                    Object nodesObj = getNodes.invoke(metadata);
                    if (nodesObj instanceof Map) {
                        Map<?, ?> nodes = (Map<?, ?>) nodesObj;
                        if (!nodes.isEmpty()) {
                            Object firstNode = nodes.values().iterator().next();
                            Method getEndPoint = firstNode.getClass().getMethod("getEndPoint");
                            Object endpoint = getEndPoint.invoke(firstNode);
                            if (endpoint != null) {
                                return endpoint.toString();
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
