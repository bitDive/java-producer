package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class NowRandomSpyAgent {

    public static AgentBuilder init(AgentBuilder agentBuilder) {

        ElementMatcher.Junction<NamedElement> targets =
                named("java.util.UUID")
                        .or(named("java.time.Instant"))
                        .or(named("java.time.LocalDate"))
                        .or(named("java.time.LocalTime"))
                        .or(named("java.time.LocalDateTime"))
                        .or(named("java.time.OffsetDateTime"))
                        .or(named("java.time.ZonedDateTime"))
                        .or(named("java.util.Random"))
                        .or(named("java.util.concurrent.ThreadLocalRandom"))
                        .or(named("java.util.SplittableRandom"));

        return agentBuilder
                .ignore(nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("io.bitdive."))
                        .or(isSynthetic()))
                .type(targets)
                .transform((builder, td, cl, module, pd) -> apply(builder, td));
    }

    private static DynamicType.Builder<?> apply(DynamicType.Builder<?> b, TypeDescription td) {
        String n = td.getName();

        if (n.equals("java.util.UUID")) {
            return b.visit(Advice.to(NowRandomAdvice.class)
                    .on(named("randomUUID").and(isStatic()).and(isPublic())));
        }

        if (n.equals("java.time.Instant")
                || n.equals("java.time.LocalDate")
                || n.equals("java.time.LocalTime")
                || n.equals("java.time.LocalDateTime")
                || n.equals("java.time.OffsetDateTime")
                || n.equals("java.time.ZonedDateTime")) {
            return b.visit(Advice.to(NowRandomAdvice.class)
                    .on(named("now").and(isStatic()).and(isPublic())));
        }

        if (n.equals("java.util.Random")) {
            return b.visit(Advice.to(NowRandomAdvice.class)
                    .on(isPublic()
                            .and(nameStartsWith("next"))
                            .and(not(named("nextBytes")))
                            .and(not(named("next").and(takesArguments(int.class))))));
        }

        return b;
    }

    public static class NowRandomAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Origin("#t") String className,
                                  @Advice.Origin("#m") String methodName,
                                  @Advice.Origin() Method method,
                                  @Advice.AllArguments Object[] args,
                                  @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable error) {


            if (Boolean.TRUE.equals(NowRandomSpyCache.IN_ADVICE.get())) {
                return;
            }
            NowRandomSpyCache.IN_ADVICE.set(Boolean.TRUE);

            try {
                // 1) msgId (сначала — чтобы не делать лишнего, если контекста нет)
                Object cached = NowRandomSpyCache.CONTEXT_MANAGER_METHOD_CACHE;
                MethodHandle mh;

                if (cached instanceof MethodHandle) {
                    mh = (MethodHandle) cached;
                } else if (cached == null) {
                    try {
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        if (cl == null) cl = ClassLoader.getSystemClassLoader();
                        Class<?> cm = Class.forName(
                                "io.bitdive.parent.trasirovka.agent.utils.ContextManager",
                                true, cl);
                        Method m = cm.getMethod("getMessageIdQueueNew");
                        mh = MethodHandles.publicLookup().unreflect(m);
                        NowRandomSpyCache.CONTEXT_MANAGER_METHOD_CACHE = mh;
                    } catch (Throwable t) {
                        NowRandomSpyCache.CONTEXT_MANAGER_METHOD_CACHE = Boolean.FALSE;
                        System.err.println("[NowRandomSpy] ContextManager not found (will not retry): " + t);
                        return;
                    }
                } else {
                    return;
                }

                String msgId = (String) mh.invoke();
                if (msgId == null || msgId.isEmpty()) return;
                // 2) prefixes — статичные данные из YAML, загружаем один раз при первом вызове
                if (NowRandomSpyCache.ALLOW_PREFIXES == null) {
                    synchronized (NowRandomSpyCache.class) {
                        if (NowRandomSpyCache.ALLOW_PREFIXES == null) {
                            NowRandomSpyCache.ALLOW_PREFIXES = NowRandomSpyCache.loadAllowPrefixesOnce();
                        }
                    }
                }
                String[] prefixes = NowRandomSpyCache.ALLOW_PREFIXES;
                // если префиксы пустые — ничего не логируем (строго "только мой код")
                if (prefixes == null || prefixes.length == 0) {
                    return;
                }

                // 3) Логируем ТОЛЬКО если прямой вызывающий (site вызова Random/now/UUID)
                //    находится в одном из пакетов из prefixes.
                //    Ищем в стеке сам инжектированный метод (className/methodName),
                //    и берём следующий фрейм как «direct caller».
                String directCaller = null;
                try {
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    for (int i = 0; i < st.length - 1; i++) {
                        StackTraceElement e = st[i];
                        if (className.equals(e.getClassName()) && methodName.equals(e.getMethodName())) {
                            directCaller = st[i + 1].getClassName();
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (directCaller == null) {
                    return;
                }

                // отсеиваем системные/агентные классы как direct caller
                if (directCaller.startsWith("java.")
                        || directCaller.startsWith("javax.")
                        || directCaller.startsWith("jdk.")
                        || directCaller.startsWith("sun.")
                        || directCaller.startsWith("com.sun.")
                        || directCaller.startsWith("net.bytebuddy.")
                        || directCaller.startsWith("io.bitdive.parent.trasirovka.agent.")) {
                    return;
                }

                boolean allowed = false;
                for (int j = 0; j < prefixes.length; j++) {
                    String p = prefixes[j];
                    if (p != null && !p.isEmpty() && directCaller.startsWith(p)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    return;
                }

                // 4) traceId/spanId (best-effort)
                String traceId = null;
                String spanId = null;
                try {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl == null) cl = ClassLoader.getSystemClassLoader();
                    Class<?> cm = Class.forName(
                            "io.bitdive.parent.trasirovka.agent.utils.ContextManager",
                            true, cl);
                    Method getTraceId = cm.getMethod("getTraceId");
                    Method getSpanId = cm.getMethod("getSpanId");
                    traceId = (String) getTraceId.invoke(null);
                    spanId = (String) getSpanId.invoke(null);
                } catch (Throwable ignored) {
                }

                // 5) MessageService.sendMessageRandomValues (как у тебя)
                Object sendCached = NowRandomSpyCache.MESSAGE_SEND_METHOD_CACHE;
                MethodHandle sendMh;

                if (sendCached instanceof MethodHandle) {
                    sendMh = (MethodHandle) sendCached;
                } else if (sendCached == null) {
                    try {
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        if (cl == null) cl = ClassLoader.getSystemClassLoader();
                        Class<?> ms = Class.forName("io.bitdive.parent.message_producer.MessageService", true, cl);
                        Method sendMethod = ms.getMethod("sendMessageRandomValues",
                                String.class, String.class, String.class,
                                Object[].class, Object.class, Object.class,
                                String.class, String.class, Method.class);
                        sendMh = MethodHandles.publicLookup().unreflect(sendMethod);
                        NowRandomSpyCache.MESSAGE_SEND_METHOD_CACHE = sendMh;
                    } catch (Throwable t) {
                        NowRandomSpyCache.MESSAGE_SEND_METHOD_CACHE = Boolean.FALSE;
                        return;
                    }
                } else {
                    return;
                }

                sendMh.invoke(msgId, traceId, spanId, args, result, error, className, methodName, method);


            } catch (Throwable ignored) {
                System.err.println("[NowRandomSpy] Exception in sendMessageRandomValues(): " + ignored);
            } finally {
                NowRandomSpyCache.IN_ADVICE.set(Boolean.FALSE);
            }
        }
    }
}
