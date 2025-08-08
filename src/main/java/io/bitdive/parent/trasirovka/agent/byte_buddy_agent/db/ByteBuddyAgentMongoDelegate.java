package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.bitdive.parent.message_producer.MessageService.sendMessageDBEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageDBStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static net.bytebuddy.matcher.ElementMatchers.named;

public class ByteBuddyAgentMongoDelegate {

    private static final String TARGET = "com.mongodb.client.internal.MongoClientDelegate$DelegateOperationExecutor";

    public static ResettableClassFileTransformer init(Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named(TARGET))
                .transform((builder, td, cl, module, pd) ->
                        builder.visit(Advice.to(ExecAdvice.class)
                                .on(named("execute"))))
                .installOn(inst);
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
                        getaNullThrowable(error),
                        MessageTypeEnum.MONGO_DB_END
                );
            }
        }
    }

    public static String getNamespace(Object op) {
        try {
            Method m = op.getClass().getMethod("getNamespace");
            Object ns = m.invoke(op);
            return ns == null ? "unknownNamespace" :
                    ns.getClass().getMethod("getDatabaseName").invoke(ns).toString();
        } catch (Exception e) {
            return "unknownNamespace";
        }
    }


    public static String buildQueryPreview(Object op) {
        String simple = op.getClass().getSimpleName();
        try {
            if (simple.contains("FindOperation")) {
                return "find " + op.getClass().getDeclaredMethod("getNamespace").invoke(op).toString() + " " +
                        op.getClass().getDeclaredMethod("getFilter").invoke(op).toString();
            }
            if (simple.contains("MixedBulkWriteOperation")) {

                Field fieldWriteRequests = op.getClass().getDeclaredField("writeRequests");
                fieldWriteRequests.setAccessible(true);
                ArrayList<Objects> arras = (ArrayList<Objects>) (fieldWriteRequests.get(op));

                StringBuilder bufRet = new StringBuilder();
                for (Object writeOperation : arras) {
                    try {
                        bufRet
                                .append(getGetMethodValues(writeOperation, "getType"))
                                .append(" ")
                                .append(getGetMethodValues(op, "getNamespace"))
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
            return writeOperation.getClass().getDeclaredMethod(methodName).invoke(writeOperation).toString();
        } catch (Exception e) {
            return "";
        }

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
