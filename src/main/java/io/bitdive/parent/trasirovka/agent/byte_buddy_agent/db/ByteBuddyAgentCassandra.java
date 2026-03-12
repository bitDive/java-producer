package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

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

public class ByteBuddyAgentCassandra {

    private static final String CQL_SESSION_FQCN =
            "com.datastax.oss.driver.api.core.CqlSession";

    private static final String BOUND_STATEMENT_FQCN =
            "com.datastax.oss.driver.api.core.cql.BoundStatement";

    private static final String METADATA_FQCN =
            "com.datastax.oss.driver.api.core.metadata.Metadata";

    /**
     * Injected into BoundStatement implementations for helper access from Advice.
     */
    public interface BitDiveCassandraBoundStatementView {
        String bitdiveQueryString();
        Object bitdiveValuesObj();
    }

    /**
     * Injected into CqlSession implementations for helper access from Advice.
     */
    public interface BitDiveCassandraSessionView {
        Object bitdiveMetadata();
    }

    /**
     * Injected into Metadata implementations for helper access from Advice.
     */
    public interface BitDiveCassandraMetadataView {
        Object bitdiveNodes();
    }

    public static AgentBuilder init(AgentBuilder agentBuilder) {
        try {
            return agentBuilder
                    .type(concreteSubtypeOf(BOUND_STATEMENT_FQCN).and(notProxyOrDelegating()))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder
                                    .implement(BitDiveCassandraBoundStatementView.class)
                                    .defineMethod("bitdiveQueryString", String.class, Visibility.PUBLIC)
                                    .intercept(MethodDelegation.to(BoundStatementQueryInterceptor.class))
                                    .defineMethod("bitdiveValuesObj", Object.class, Visibility.PUBLIC)
                                    .intercept(MethodDelegation.to(BoundStatementValuesInterceptor.class))
                    )

                    .type(concreteSubtypeOf(METADATA_FQCN))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder
                                    .implement(BitDiveCassandraMetadataView.class)
                                    .defineMethod("bitdiveNodes", Object.class, Visibility.PUBLIC)
                                    .intercept(MethodDelegation.to(MetadataNodesInterceptor.class))
                    )

                    .type(concreteSubtypeOf(CQL_SESSION_FQCN).and(notProxyOrDelegating()))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder
                                    .implement(BitDiveCassandraSessionView.class)
                                    .defineMethod("bitdiveMetadata", Object.class, Visibility.PUBLIC)
                                    .intercept(MethodDelegation.to(SessionMetadataInterceptor.class))
                                    .visit(Advice.to(CassandraAdvice.class)
                                            .on(ElementMatchers.named("execute")
                                                    .and(ElementMatchers.not(ElementMatchers.nameContains("Internal")))))
                    );
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("Failed to initialize Cassandra ByteBuddy agent: " + e.getMessage());
            }
            return agentBuilder;
        }
    }

    private static ElementMatcher.Junction<TypeDescription> concreteSubtypeOf(String fqcn) {
        return ElementMatchers.hasSuperType(ElementMatchers.named(fqcn))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract()));
    }

    private static ElementMatcher.Junction<TypeDescription> notProxyOrDelegating() {
        return ElementMatchers.not(ElementMatchers.nameContains("Proxy"))
                .and(ElementMatchers.not(ElementMatchers.nameContains("Delegating")));
    }

    public static class BoundStatementQueryInterceptor {

        @RuntimeType
        public static String intercept(@This Object self) {
            try {
                Object preparedStatement = invokeNoArgMethod(self, "getPreparedStatement");
                if (preparedStatement == null) {
                    return null;
                }

                Object query = invokeNoArgMethod(preparedStatement, "getQuery");
                return query != null ? query.toString() : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static class BoundStatementValuesInterceptor {

        @RuntimeType
        public static Object intercept(@This Object self) {
            try {
                try {
                    return invokeNoArgMethod(self, "getValues");
                } catch (NoSuchMethodException ignored) {
                    // fallback to field below
                }

                try {
                    return readField(self, "values");
                } catch (NoSuchFieldException ignored) {
                    return null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static class SessionMetadataInterceptor {

        @RuntimeType
        public static Object intercept(@This Object self) {
            try {
                return invokeNoArgMethod(self, "getMetadata");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static class MetadataNodesInterceptor {

        @RuntimeType
        public static Object intercept(@This Object self) {
            try {
                return invokeNoArgMethod(self, "getNodes");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Object invokeNoArgMethod(Object target, String methodName) throws Exception {
        Method method = findNoArgMethod(target.getClass(), methodName);
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }
        return method.invoke(target);
    }

    private static Method findNoArgMethod(Class<?> type, String methodName) throws NoSuchMethodException {
        try {
            return type.getMethod(methodName);
        } catch (NoSuchMethodException ignored) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod(methodName);
                } catch (NoSuchMethodException ignoredInner) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchMethodException("Method not found: " + methodName + " in " + type.getName());
        }
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }
        return field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field not found: " + fieldName + " in " + type.getName());
    }

    public static class CassandraAdvice {

        @Advice.OnMethodEnter
        public static MethodContext onEnter(@Advice.This Object session,
                                            @Advice.Origin Method method,
                                            @Advice.AllArguments Object[] args) {
            MethodContext context = new MethodContext();

            if (LoggerStatusContent.getEnabledProfile()) {
                return context;
            }

            if (args == null || args.length == 0 || args[0] == null) {
                context.flagNoMonitoring = true;
                return context;
            }

            Object request = args[0];
            String query = extractQuery(request);

            if (query == null || query.trim().isEmpty()) {
                context.flagNoMonitoring = true;
                return context;
            }

            String connectionInfo = "cassandra//:" + extractConnectionAddress(session);

            context.flagNoMonitoring = false;
            context.traceId = ContextManager.getTraceId();
            context.spanId = ContextManager.getSpanId();
            context.UUIDMessage = UuidCreator.getTimeBased().toString();

            try {
                if (!ContextManager.isMessageIdQueueEmpty()) {
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
            if (LoggerStatusContent.getEnabledProfile()) {
                return;
            }

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
            if (request == null) {
                return null;
            }

            // 1. execute(String)
            if (request instanceof CharSequence) {
                return request.toString();
            }

            String query = null;

            // 2. execute(BoundStatement)
            if (request instanceof BitDiveCassandraBoundStatementView) {
                try {
                    query = ((BitDiveCassandraBoundStatementView) request).bitdiveQueryString();
                } catch (Exception ignored) {
                }
            }

            // 3. Best-effort for SimpleStatement / other statement types
            if (query == null || query.isEmpty()) {
                query = tryExtractQueryByReflection(request);
            }

            // 4. Fallback
            if (query == null || query.isEmpty()) {
                query = request.toString();
            }

            // Подстановка параметров только для BoundStatement-подобных объектов
            if (isBoundStatementLike(request)) {
                Object[] params = extractParameters(request);
                if (params != null && params.length > 0) {
                    query = fillQueryParameters(query, params);
                }
            }

            return query;
        }

        private static boolean isBoundStatementLike(Object request) {
            if (request == null) {
                return false;
            }
            String className = request.getClass().getName();
            String simpleName = request.getClass().getSimpleName();
            return className.contains("BoundStatement") || simpleName.contains("BoundStatement");
        }

        private static String tryExtractQueryByReflection(Object request) {
            try {
                Method getQuery = findNoArgMethod(request.getClass(), "getQuery");
                if (getQuery != null) {
                    if (!getQuery.isAccessible()) {
                        getQuery.setAccessible(true);
                    }
                    Object queryObj = getQuery.invoke(request);
                    if (queryObj != null) {
                        return queryObj.toString();
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Method getPreparedStatement = findNoArgMethod(request.getClass(), "getPreparedStatement");
                if (getPreparedStatement != null) {
                    if (!getPreparedStatement.isAccessible()) {
                        getPreparedStatement.setAccessible(true);
                    }
                    Object preparedStatement = getPreparedStatement.invoke(request);
                    if (preparedStatement != null) {
                        Method getQuery = findNoArgMethod(preparedStatement.getClass(), "getQuery");
                        if (getQuery != null) {
                            if (!getQuery.isAccessible()) {
                                getQuery.setAccessible(true);
                            }
                            Object queryObj = getQuery.invoke(preparedStatement);
                            if (queryObj != null) {
                                return queryObj.toString();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            return null;
        }

        private static Method findNoArgMethod(Class<?> type, String methodName) {
            try {
                return type.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
            }

            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredMethod(methodName);
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }

            return null;
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
            if (query == null || query.isEmpty() || params == null || params.length == 0) {
                return query;
            }

            for (Object param : params) {
                String value = (param == null) ? "null" : paramToString(param);
                query = query.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(value));
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