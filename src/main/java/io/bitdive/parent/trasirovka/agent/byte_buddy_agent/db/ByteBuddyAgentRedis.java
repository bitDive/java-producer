package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.MessageTypeEnum;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;

import java.time.OffsetDateTime;

import static io.bitdive.parent.message_producer.MessageService.*;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

public class ByteBuddyAgentRedis {

    public static ResettableClassFileTransformer init(Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)

                // 2) Jedis Commands
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("redis.clients.jedis.commands.JedisCommands")))
                .transform((builder, td, cl, m, pd) ->
                        builder.visit(Advice.to(RedisAdvice.class)
                                .on(ElementMatchers.isPublic()
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("toString")))
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("close"))))))

                // 3) Spring Data low‑level
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.named("org.springframework.data.redis.connection.RedisConnection")))
                .transform((builder, td, cl, m, pd) ->
                        builder.visit(Advice.to(RedisAdvice.class)
                                .on(ElementMatchers.isPublic()
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("toString"))))))

                // 4) Spring Data high‑level Operations<*,*>
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.nameStartsWith("org.springframework.data.redis.core")
                                .and(ElementMatchers.nameContains("Operations"))))
                .transform((builder, td, cl, m, pd) ->
                        builder.visit(Advice.to(RedisAdvice.class)
                                .on(ElementMatchers.isPublic())))


                // 1) Lettuce sync API
                .type(ElementMatchers.hasSuperType(
                        ElementMatchers.nameStartsWith("io.lettuce.core.api.sync")
                                .and(ElementMatchers.nameContains("Commands"))))
                .transform((builder, td, cl, m, pd) ->
                        builder.visit(Advice.to(RedisAdvice.class)
                                .on(ElementMatchers.isVirtual()
                                        .and(ElementMatchers.isPublic())
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("toString")))
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("close")))
                                        .and(ElementMatchers.not(ElementMatchers.nameStartsWith("getStatefulConnection")))
                                )
                        ))

                .installOn(inst);
    }

    public static class RedisContext {
        public String uuid;
        public String traceId;
        public String spanId;
        public String operation;
        public String args;
        public String connectionUrl;
    }

    public static class RedisAdvice {
        @Advice.OnMethodEnter
        public static RedisContext onEnter(@Advice.This Object thiz,
                                           @Advice.Origin("#m") String methodName,
                                           @Advice.AllArguments Object[] arguments) {
            RedisContext ctx = new RedisContext();
            if (LoggerStatusContent.getEnabledProfile()) return ctx;
            ctx.operation = methodName;
            ctx.args = ReflectionUtils.objectToString(arguments);
            ctx.uuid = UuidCreator.getTimeBased().toString();
            ctx.traceId = ContextManager.getTraceId();
            ctx.spanId = ContextManager.getSpanId();
            ctx.connectionUrl = extractRedisUrl(thiz);

            try {
                sendMessageDBStart(
                        ctx.uuid,
                        ctx.traceId,
                        ctx.spanId,
                        ctx.operation + ctx.args,
                        ctx.connectionUrl,
                        OffsetDateTime.now(),
                        ContextManager.getMessageIdQueueNew(),
                        MessageTypeEnum.REDIS_DB_START
                );
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending Redis START: " + e.getMessage());
                }
            }
            return ctx;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter RedisContext ctx,
                                  @Advice.Thrown Throwable throwable,
                                  @Advice.Return(readOnly = true, typing = DYNAMIC) Object returnValue) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                sendMessageDBRedisEnd(
                        ctx.uuid,
                        ctx.traceId,
                        ctx.spanId,
                        OffsetDateTime.now(),
                        getaNullThrowable(throwable),
                        MessageTypeEnum.REDIS_DB_END,
                        ReflectionUtils.objectToString(returnValue)
                );
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error sending Redis END: " + e.getMessage());
                }
            }
        }

        public static Class<?> HANDLER_CLASS;
        public static Field CONNECTION_FIELD;
        public static Field HANDLER_CONN;
        public static Field HANDLER_WRITER;
        public static Field ENDPOINT_CHANNEL;
        public static Class<?> CHANNEL_CLASS;

        static {
            try {
                // 1. Загружаем класс FutureSyncInvocationHandler по имени
                HANDLER_CLASS = Class.forName("io.lettuce.core.FutureSyncInvocationHandler");
                CONNECTION_FIELD = HANDLER_CLASS.getDeclaredField("connection");
                CONNECTION_FIELD.setAccessible(true);

                // 1. FutureSyncInvocationHandler.connection
                Class<?> fsh = Class.forName("io.lettuce.core.FutureSyncInvocationHandler");
                HANDLER_CONN = fsh.getDeclaredField("connection");
                HANDLER_CONN.setAccessible(true);

                // 2. RedisChannelHandler.channelWriter
                Class<?> rch = Class.forName("io.lettuce.core.RedisChannelHandler");
                HANDLER_WRITER = rch.getDeclaredField("channelWriter");
                HANDLER_WRITER.setAccessible(true);

                // 4. DefaultEndpoint.channel
                Class<?> dep = Class.forName("io.lettuce.core.protocol.DefaultEndpoint");
                ENDPOINT_CHANNEL = dep.getDeclaredField("channel");
                ENDPOINT_CHANNEL.setAccessible(true);

                CHANNEL_CLASS = Class.forName("io.netty.channel.Channel");

            } catch (Exception e) {
            }
        }

        public static String extractRedisUrl(Object thiz) {
            try {
                try {
                    Class<?> lettuceConn = Class.forName("org.springframework.data.redis.core.AbstractOperations");
                    if (lettuceConn.isInstance(thiz)) {
                        Field fieldRedisSpringTemplate = lettuceConn.getDeclaredField("template");
                        fieldRedisSpringTemplate.setAccessible(true);
                        Object valRedisSpringTemplate = fieldRedisSpringTemplate.get(thiz);
                        Class<?> redisAccessor = Class.forName("org.springframework.data.redis.core.RedisAccessor");
                        Object valConnectionFactory = redisAccessor.getMethod("getConnectionFactory").invoke(valRedisSpringTemplate);

                        Field ff = valConnectionFactory.getClass().getDeclaredField("configuration");
                        ff.setAccessible(true);
                        Object valConnectRedis = ff.get(valConnectionFactory);

                        return "redis://" + valConnectRedis.getClass().getMethod("getHostName").invoke(valConnectRedis) + ":" +
                                valConnectRedis.getClass().getMethod("getPort").invoke(valConnectRedis);
                    }
                } catch (Exception e) {
                }

                try {
                    InvocationHandler handler = Proxy.getInvocationHandler(thiz);
                    if (HANDLER_CLASS.isInstance(handler)) {
                        // 4. Извлекаем connection
                        Object connection = CONNECTION_FIELD.get(handler);

                        // Распаковали writer до DefaultEndpoint/ PubSubEndpoint
                        Object writer = HANDLER_WRITER.get(connection);
                        // … ваш цикл getDelegate() …

                        // 1) Достаем поле channel (Netty Channel) как Object
                        Object channelObj = ENDPOINT_CHANNEL.get(writer);

                        // 2) Проверяем, что это именно io.netty.channel.Channel
                        if (CHANNEL_CLASS.isInstance(channelObj)) {

                            // 3) Вызываем метод remoteAddress() у Netty‑канала
                            Method mRemote = CHANNEL_CLASS.getMethod("remoteAddress");
                            Object addrObj = mRemote.invoke(channelObj);

                            if (addrObj instanceof InetSocketAddress) {
                                InetSocketAddress inet = (InetSocketAddress) addrObj;
                                return "redis://" + inet.getHostString() + ":" + inet.getPort();
                            }
                        }
                    }
                } catch (Exception e) {
                }


                // Lettuce sync commands
                Class<?> syncCmds = Class.forName("io.lettuce.core.api.sync.RedisCommands");
                if (syncCmds.isInstance(thiz)) {
                    Method getConn = thiz.getClass().getMethod("getStatefulConnection");
                    Object conn = getConn.invoke(thiz);
                    return extractRedisUrl(conn);
                }
                // Jedis / JedisCluster
                Class<?> jedisClass = Class.forName("redis.clients.jedis.Jedis");
                if (jedisClass.isInstance(thiz)) {
                    Method host = jedisClass.getMethod("getClient");
                    Object client = host.invoke(thiz);
                    Method getHost = client.getClass().getMethod("getHost");
                    Method getPort = client.getClass().getMethod("getPort");
                    return "redis://" +
                            getHost.invoke(client) + ":" +
                            getPort.invoke(client);
                }
                Class<?> clusterClass = Class.forName("redis.clients.jedis.JedisCluster");
                if (clusterClass.isInstance(thiz)) {
                    Method m = clusterClass.getMethod("getClusterNodes");
                    Object nodes = m.invoke(thiz); // Map<HostAndPort,...>
                    if (nodes instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) nodes;
                        if (!map.isEmpty()) {
                            Object addr = map.keySet().iterator().next();
                            Method getHost = addr.getClass().getMethod("getHost");
                            Method getPort = addr.getClass().getMethod("getPort");
                            return "redis://" +
                                    getHost.invoke(addr) + ":" +
                                    getPort.invoke(addr);
                        }
                    }
                }

            } catch (Exception ignored) {
            }
            return "";
        }
    }
}
