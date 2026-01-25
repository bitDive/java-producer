package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public class ByteBuddyAgentMongoDelegate {

    private static final String TARGET = "com.mongodb.client.internal.MongoClientDelegate$DelegateOperationExecutor";

    /**
     * Injected into operation objects that provide getNamespace().
     */
    public interface BitDiveMongoNamespaceOwnerView {
        Object bitdiveNamespace();
    }

    /**
     * Injected into namespace objects that provide getDatabaseName().
     */
    public interface BitDiveMongoNamespaceView {
        String bitdiveDatabaseName();
    }

    /**
     * Injected into FindOperation-like objects that provide getFilter().
     */
    public interface BitDiveMongoFindOperationView extends BitDiveMongoNamespaceOwnerView {
        Object bitdiveFilter();
    }

    /**
     * Injected into MixedBulkWriteOperation-like objects that contain writeRequests field.
     */
    public interface BitDiveMongoMixedBulkWriteView extends BitDiveMongoNamespaceOwnerView {
        List<?> bitdiveWriteRequests();
    }

    /**
     * Injected into write request entries to avoid reflection on getType/getFilter/getUpdateValue.
     */
    public interface BitDiveMongoWriteRequestView {
        Object bitdiveType();
        Object bitdiveFilter();
        Object bitdiveUpdateValue();
    }

    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        return agentBuilder
                // Namespace owner view: any Mongo op that has getNamespace()
                .type(td -> td.getName().startsWith("com.mongodb")
                        && !td.isInterface()
                        && !td.getDeclaredMethods().filter(named("getNamespace")).isEmpty())
                .transform((builder, td, cl, module, pd) -> {
                    try {
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mGetNs =
                                td.getDeclaredMethods().filter(named("getNamespace").and(takesArguments(0))).getOnly();
                        return builder
                                .implement(BitDiveMongoNamespaceOwnerView.class)
                                .defineMethod("bitdiveNamespace", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mGetNs));
                    } catch (Exception e) {
                        return builder;
                    }
                })

                // Namespace view: getDatabaseName()
                .type(td -> td.getName().startsWith("com.mongodb")
                        && !td.isInterface()
                        && !td.getDeclaredMethods().filter(named("getDatabaseName")).isEmpty())
                .transform((builder, td, cl, module, pd) -> {
                    try {
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mDb =
                                td.getDeclaredMethods().filter(named("getDatabaseName").and(takesArguments(0))).getOnly();
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mToString =
                                new net.bytebuddy.description.method.MethodDescription.ForLoadedMethod(Object.class.getMethod("toString"));
                        return builder
                                .implement(BitDiveMongoNamespaceView.class)
                                .defineMethod("bitdiveDatabaseName", String.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mToString)
                                        .onMethodCall(net.bytebuddy.implementation.MethodCall.invoke(mDb)));
                    } catch (Exception e) {
                        return builder;
                    }
                })

                // FindOperation-like view: getFilter()
                .type(td -> td.getName().contains("FindOperation")
                        && !td.isInterface()
                        && !td.getDeclaredMethods().filter(named("getFilter")).isEmpty())
                .transform((builder, td, cl, module, pd) -> {
                    try {
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mFilter =
                                td.getDeclaredMethods().filter(named("getFilter").and(takesArguments(0))).getOnly();
                        return builder
                                .implement(BitDiveMongoFindOperationView.class)
                                .defineMethod("bitdiveFilter", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mFilter));
                    } catch (Exception e) {
                        return builder;
                    }
                })

                // MixedBulkWriteOperation writeRequests field view
                .type(td -> td.getName().contains("MixedBulkWriteOperation")
                        && !td.isInterface()
                        && !td.getDeclaredFields().filter(net.bytebuddy.matcher.ElementMatchers.named("writeRequests")).isEmpty())
                .transform((builder, td, cl, module, pd) -> builder
                        .implement(BitDiveMongoMixedBulkWriteView.class)
                        .defineMethod("bitdiveWriteRequests", List.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                        .intercept(net.bytebuddy.implementation.FieldAccessor.ofField("writeRequests")))

                // WriteRequest entry view: getType/getFilter/getUpdateValue
                .type(td -> td.getName().startsWith("com.mongodb")
                        && !td.isInterface()
                        && !td.getDeclaredMethods().filter(named("getType")).isEmpty()
                        && !td.getDeclaredMethods().filter(named("getFilter")).isEmpty()
                        && !td.getDeclaredMethods().filter(named("getUpdateValue")).isEmpty())
                .transform((builder, td, cl, module, pd) -> {
                    try {
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mType =
                                td.getDeclaredMethods().filter(named("getType").and(takesArguments(0))).getOnly();
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mFilter =
                                td.getDeclaredMethods().filter(named("getFilter").and(takesArguments(0))).getOnly();
                        net.bytebuddy.description.method.MethodDescription.InDefinedShape mUpd =
                                td.getDeclaredMethods().filter(named("getUpdateValue").and(takesArguments(0))).getOnly();
                        return builder
                                .implement(BitDiveMongoWriteRequestView.class)
                                .defineMethod("bitdiveType", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mType))
                                .defineMethod("bitdiveFilter", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mFilter))
                                .defineMethod("bitdiveUpdateValue", Object.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
                                .intercept(net.bytebuddy.implementation.MethodCall.invoke(mUpd));
                    } catch (Exception e) {
                        return builder;
                    }
                })

                .type(named(TARGET))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(ExecAdvice.class)
                                .on(named("execute"))));
    }


    public static class ExecAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static MethodCtx onEnter(@Advice.This Object exec,
                                        @Advice.AllArguments Object[] args) {


            MethodCtx ctx = new MethodCtx();
            if (LoggerStatusContent.getEnabledProfile()) return ctx;
            ctx.skip = true;

            Object op = args[0];                       // ReadOperation / WriteOperation
            String namespace = getNamespace(op);
            String query = buildQueryPreview(op);
            String server = getClusterHosts(exec);
            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();
            ctx.uuid = UuidCreator.getTimeBased().toString();

            if (!ContextManager.isMessageIdQueueEmpty()) {
                ctx.skip = false;
                sendMessageDBStart(
                        ctx.uuid,
                        ctx.traceId,
                        ctx.spanId,
                        query, "mongoDB//:" + server + "@" + namespace,
                        OffsetDateTime.now(),
                        ContextManager.getMessageIdQueueNew(),
                        MessageTypeEnum.MONGO_DB_START
                );
            }

            return ctx;

        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Enter MethodCtx ctx,
                                  @Advice.Thrown Throwable error) {

            if (LoggerStatusContent.getEnabledProfile()) return;
            if (ctx.skip) return;
            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageDBEnd(
                        ctx.uuid,
                        ctx.traceId,
                        ctx.spanId,
                        OffsetDateTime.now(),
                        ReflectionUtils.objectToString(error),
                        MessageTypeEnum.MONGO_DB_END
                );
            }
        }
    }

    public static String getNamespace(Object op) {
        try {
            if (op instanceof BitDiveMongoNamespaceOwnerView) {
                Object ns = ((BitDiveMongoNamespaceOwnerView) op).bitdiveNamespace();
                if (ns instanceof BitDiveMongoNamespaceView) {
                    return ((BitDiveMongoNamespaceView) ns).bitdiveDatabaseName();
                }
                return ns == null ? "unknownNamespace" : ns.toString();
            }
        } catch (Exception ignored) {
        }
        return "unknownNamespace";
    }


    public static String buildQueryPreview(Object op) {
        String simple = op.getClass().getSimpleName();
        try {
            if (simple.contains("FindOperation")) {
                if (op instanceof BitDiveMongoFindOperationView) {
                    BitDiveMongoFindOperationView v = (BitDiveMongoFindOperationView) op;
                    Object ns = v.bitdiveNamespace();
                    Object filter = v.bitdiveFilter();
                    return "find " + String.valueOf(ns) + " " + String.valueOf(filter);
                }
                return "find " + getNamespace(op);
            }
            if (simple.contains("MixedBulkWriteOperation")) {

                List<?> arras = null;
                if (op instanceof BitDiveMongoMixedBulkWriteView) {
                    arras = ((BitDiveMongoMixedBulkWriteView) op).bitdiveWriteRequests();
                }
                if (arras == null) return simple;

                StringBuilder bufRet = new StringBuilder();
                for (Object writeOperation : arras) {
                    try {
                        bufRet
                                .append(getGetMethodValues(writeOperation, "getType"))
                                .append(" ")
                                .append(getNamespace(op))
                                .append(" ")
                                .append(getGetMethodValues(writeOperation, "getUpdateValue"))
                                .append(" filter: ")
                                .append(getGetMethodValues(writeOperation, "getFilter"))
                                .append(",");
                    } catch (Exception e) {
                        bufRet.append("unknownType").append(",");
                    }
                }

                if (bufRet.length() > 0) {
                    bufRet.setLength(bufRet.length() - 1);
                }

                return "[" + bufRet + "]";
            }
        } catch (Exception ignore) {
        }
        return simple;
    }

    public static String getGetMethodValues(Object writeOperation, String methodName) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        try {
            if (writeOperation instanceof BitDiveMongoWriteRequestView) {
                BitDiveMongoWriteRequestView v = (BitDiveMongoWriteRequestView) writeOperation;
                Object val;
                if ("getType".equals(methodName)) val = v.bitdiveType();
                else if ("getFilter".equals(methodName)) val = v.bitdiveFilter();
                else if ("getUpdateValue".equals(methodName)) val = v.bitdiveUpdateValue();
                else val = null;
                return val == null ? "" : val.toString();
            }
        } catch (Exception ignored) {
        }
        return "";

    }

    public static String getClusterHosts(Object executor) {
        try {
            Field outer = executor.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            Object delegate = outer.get(executor);
            Method getCluster = delegate.getClass().getMethod("getCluster");
            getCluster.setAccessible(true);
            Object cluster = getCluster.invoke(delegate);
            Method getDesc = cluster.getClass().getMethod("getCurrentDescription");
            getDesc.setAccessible(true);
            Object desc = getDesc.invoke(cluster);
            Method serversM = desc.getClass().getMethod("getServerDescriptions");
            serversM.setAccessible(true);
            List<?> servers = (List<?>) serversM.invoke(desc);
            if (servers != null && !servers.isEmpty()) {
                return "[" + servers.stream()
                        .map(server -> {
                                    try {
                                        Method addressServer = server.getClass().getDeclaredMethod("getAddress");
                                        addressServer.setAccessible(true);
                                        return addressServer.invoke(server);
                                    } catch (Exception ignore) {
                                        return null;
                                    }
                                }
                        )
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.joining(",")) + "]";
            }
        } catch (Exception ignore) {
        }
        return "unknownServer";
    }

    /* DTO для передачи между enter/exit */
    public static class MethodCtx {
        public boolean skip;
        public String traceId;
        public String spanId;
        public String uuid;
    }

    public static void logErr(String msg, Throwable t) {
        if (LoggerStatusContent.isErrorsOrDebug()) {
            System.err.println(msg + ": " + t.getMessage());
        }
    }
}
