package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.OutgoingRestTemplateBodyContext;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.utils.Pair;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Callable;

import static io.bitdive.parent.message_producer.MessageService.sendMessageRequestUrl;
import static io.bitdive.parent.trasirovka.agent.utils.RestUtils.normalizeResponseBodyBytes;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class ByteBuddyAgentRestTemplateRequestWeb {

    // =========================
    // Interfaces (JDK-only)
    // =========================

    public interface BitDiveSpringHttpRequestView {
        String bitdiveUriString();
        String bitdiveMethodString();
        Object bitdiveHeaders(); // фактически HttpHeaders/MultiValueMap, но как Object
    }

    public interface BitDiveSpringHttpResponseView {
        Object bitdiveHeaders();

        int bitdiveStatusCodeValue(); // универсально для Spring 4/5/6

        byte[] bitdiveCachedBody();
        void bitdiveSetCachedBody(byte[] bytes);

        InputStream bitdiveGetBody();
    }

    // =========================
    // Config
    // =========================

    private static final String RESPONSE_CACHED_BODY_FIELD = "bitdive$cachedBody";
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024; // 2MB

    // =========================
    // Agent init
    // =========================

    public static Pair<AgentBuilder, AgentBuilder> init(AgentBuilder agentBuilder,
                                                        AgentBuilder agentBuilderTransform) {

        // 0) Ловим исходный body-объект из RestTemplate callback (до записи в stream)
        try {
            agentBuilder = agentBuilder
                    .type(named("org.springframework.web.client.RestTemplate$HttpEntityRequestCallback"))
                    .transform((builder, td, cl, module, dd) ->
                            builder.method(named("doWithRequest"))
                                    .intercept(MethodDelegation.to(RestTemplateHttpEntityRequestCallbackInterceptor.class))
                    );
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("RestTemplate$HttpEntityRequestCallback not found/failed: " + e);
            }
        }

        // 1) Response: интерфейс + поле кеша + перехват getBody()
        try {
            agentBuilderTransform = agentBuilderTransform
                    .type(hasSuperType(named("org.springframework.http.client.ClientHttpResponse"))
                            .and(not(isInterface())))
                    .transform((builder, td, cl, module, dd) -> {

                        MethodDescription.InDefinedShape mGetHeaders =
                                findMethodInHierarchy(td, "getHeaders", 0);
                        MethodDescription.InDefinedShape mGetBody =
                                findMethodInHierarchy(td, "getBody", 0);

                        // getStatusCode может отсутствовать только в очень старых экзотических реализациях,
                        // но нам он теперь НЕ нужен на этапе трансформации (берём рантаймом).
                        if (mGetHeaders == null || mGetBody == null) {
                            return builder;
                        }

                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("BitDive: instrument ClientHttpResponse: " + td.getName());
                        }

                        return builder
                                // поле кеша
                                .defineField(RESPONSE_CACHED_BODY_FIELD, byte[].class, Visibility.PRIVATE)

                                // интерфейс
                                .implement(BitDiveSpringHttpResponseView.class)

                                // getter/setter кеша
                                .defineMethod("bitdiveCachedBody", byte[].class, Visibility.PUBLIC)
                                .intercept(FieldAccessor.ofField(RESPONSE_CACHED_BODY_FIELD))

                                .defineMethod("bitdiveSetCachedBody", void.class, Visibility.PUBLIC)
                                .withParameters(byte[].class)
                                .intercept(FieldAccessor.ofField(RESPONSE_CACHED_BODY_FIELD))

                                // headers
                                .defineMethod("bitdiveHeaders", Object.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetHeaders))

                                // ✅ универсальный status-code (Spring 4/5/6)
                                .defineMethod("bitdiveStatusCodeValue", int.class, Visibility.PUBLIC)
                                .intercept(MethodDelegation.to(ClientHttpResponseStatusCodeValueInterceptor.class))

                                // body accessor (вызывает getBody(); может попасть в интерцептор — и это нормально)
                                .defineMethod("bitdiveGetBody", InputStream.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetBody))

                                // перехват getBody() для кеширования
                                .method(is(mGetBody))
                                .intercept(MethodDelegation.to(ClientHttpResponseGetBodyInterceptor.class));
                    });

        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("ClientHttpResponse transform failed: " + e);
            }
        }

        // 2) Request: интерфейс + перехват execute()
        try {
            agentBuilderTransform = agentBuilderTransform
                    .type(hasSuperType(named("org.springframework.http.client.ClientHttpRequest"))
                            .and(not(isInterface())))
                    .transform((builder, td, cl, module, dd) -> {

                        MethodDescription.InDefinedShape mGetURI =
                                findMethodInHierarchy(td, "getURI", 0);

                        // Spring 6+ may expose getMethodValue(), older: getMethod()
                        MethodDescription.InDefinedShape mGetMethodValue =
                                findMethodInHierarchy(td, "getMethodValue", 0);
                        MethodDescription.InDefinedShape mGetMethod =
                                (mGetMethodValue == null) ? findMethodInHierarchy(td, "getMethod", 0) : null;

                        MethodDescription.InDefinedShape mGetHeaders =
                                findMethodInHierarchy(td, "getHeaders", 0);

                        if (mGetURI == null || (mGetMethodValue == null && mGetMethod == null) || mGetHeaders == null) {
                            return builder;
                        }

                        MethodDescription.InDefinedShape mToString;
                        try {
                            mToString = new MethodDescription.ForLoadedMethod(Object.class.getMethod("toString"));
                        } catch (NoSuchMethodException ex) {
                            return builder;
                        }

                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("BitDive: instrument ClientHttpRequest: " + td.getName());
                        }

                        builder = builder
                                .implement(BitDiveSpringHttpRequestView.class)

                                // String bitdiveUriString() { return getURI().toString(); }
                                .defineMethod("bitdiveUriString", String.class, Visibility.PUBLIC)
                                .intercept(
                                        MethodCall.invoke(mToString)
                                                .onMethodCall(MethodCall.invoke(mGetURI))
                                )

                                // Object bitdiveHeaders() { return getHeaders(); }
                                .defineMethod("bitdiveHeaders", Object.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetHeaders))

                                // execute() -> наш интерцептор
                                .method(named("execute").and(takesArguments(0)))
                                .intercept(MethodDelegation.to(ResponseWeRestTemplateInterceptor.class));

                        // method string
                        if (mGetMethodValue != null) {
                            builder = builder
                                    .defineMethod("bitdiveMethodString", String.class, Visibility.PUBLIC)
                                    .intercept(MethodCall.invoke(mGetMethodValue));
                        } else {
                            builder = builder
                                    .defineMethod("bitdiveMethodString", String.class, Visibility.PUBLIC)
                                    .intercept(
                                            MethodCall.invoke(mToString)
                                                    .onMethodCall(MethodCall.invoke(mGetMethod))
                                    );
                        }

                        return builder;
                    });

        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("ClientHttpRequest transform failed: " + e);
            }
        }

        return Pair.createPair(agentBuilder, agentBuilderTransform);
    }

    // =========================
    // Interceptors
    // =========================

    /**
     * Captures original body object from RestTemplate callback, before it is written into ClientHttpRequest#getBody().
     */
    public static class RestTemplateHttpEntityRequestCallbackInterceptor {
        @RuntimeType
        public static Object intercept(@SuperCall Callable<?> zuper,
                                       @This Object callback) throws Throwable {
            try {
                Object body = extractBodyFromCallback(callback);
                if (body != null) {
                    OutgoingRestTemplateBodyContext.set(body);
                }
            } catch (Exception ignored) {
            }
            return zuper.call();
        }

        private static Object extractBodyFromCallback(Object callback) {
            if (callback == null) return null;

            // common fields in Spring RestTemplate internals
            Object httpEntity = getFieldValueQuietly(callback, "requestEntity");
            if (httpEntity != null) {
                Object body = invokeMethodQuietly(httpEntity, "getBody");
                if (body != null) return body;
            }

            Object requestBody = getFieldValueQuietly(callback, "requestBody");
            if (requestBody != null) return requestBody;

            // fallback: scan declared fields for something with getBody()
            try {
                Class<?> c = callback.getClass();
                while (c != null) {
                    for (Field f : c.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object v = f.get(callback);
                        if (v == null) continue;
                        Object b = invokeMethodQuietly(v, "getBody");
                        if (b != null) return b;
                    }
                    c = c.getSuperclass();
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private static Object getFieldValueQuietly(Object target, String fieldName) {
            try {
                Class<?> c = target.getClass();
                while (c != null) {
                    try {
                        Field f = c.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        return f.get(target);
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private static Object invokeMethodQuietly(Object target, String methodName) {
            try {
                Method m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
                try {
                    Method m = target.getClass().getDeclaredMethod(methodName);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (Exception ignored2) {
                    return null;
                }
            }
        }
    }

    /**
     * Universal status code resolver:
     * - try getRawStatusCode() first (older Spring)
     * - else getStatusCode().value() (works for HttpStatus and HttpStatusCode)
     */
    public static class ClientHttpResponseStatusCodeValueInterceptor {

        @RuntimeType
        public static int intercept(@This Object self) {
            Integer raw = invokeIntNoArgPublicFirst(self, "getRawStatusCode");
            if (raw != null) return raw;

            Object sc = invokeObjNoArgPublicFirst(self, "getStatusCode");
            if (sc != null) {
                Integer v = invokeIntNoArgPublicFirst(sc, "value");
                if (v != null) return v;
            }

            return -1;
        }

        private static Object invokeObjNoArgPublicFirst(Object target, String name) {
            try {
                Method m = findNoArgPublicFirst(target.getClass(), name);
                if (m == null) return null;
                return m.invoke(target);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Integer invokeIntNoArgPublicFirst(Object target, String name) {
            try {
                Method m = findNoArgPublicFirst(target.getClass(), name);
                if (m == null) return null;
                Object r = m.invoke(target);
                if (r instanceof Integer) return (Integer) r;
                if (r instanceof Number) return ((Number) r).intValue();
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Method findNoArgPublicFirst(Class<?> c, String name) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }

            Class<?> cur = c;
            while (cur != null) {
                try {
                    Method m = cur.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
                cur = cur.getSuperclass();
            }
            return null;
        }
    }

    /**
     * Caches body once. If maxBytes reached, continues draining the stream (discarding),
     * to keep HTTP connection reusable.
     */
    public static class ClientHttpResponseGetBodyInterceptor {
        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object self) throws Exception {

            if (!(self instanceof BitDiveSpringHttpResponseView)) {
                return zuper.call();
            }

            BitDiveSpringHttpResponseView view = (BitDiveSpringHttpResponseView) self;

            byte[] cached = view.bitdiveCachedBody();
            if (cached != null) {
                return new ByteArrayInputStream(cached);
            }

            Object original = zuper.call();
            if (!(original instanceof InputStream)) {
                return original;
            }

            byte[] bytes;
            try (InputStream in = (InputStream) original) {
                bytes = readUpToAndDrain(in, MAX_BODY_BYTES);
            }

            view.bitdiveSetCachedBody(bytes);
            return new ByteArrayInputStream(bytes);
        }

        private static byte[] readUpToAndDrain(InputStream in, int maxBytes) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, maxBytes));
            byte[] buf = new byte[8192];

            int total = 0;
            int n;

            while ((n = in.read(buf)) != -1) {
                if (total < maxBytes) {
                    int canWrite = Math.min(n, maxBytes - total);
                    if (canWrite > 0) {
                        out.write(buf, 0, canWrite);
                        total += canWrite;
                    }
                }
                // если уже достигли лимита — просто продолжаем читать/выкидывать до EOF (drain)
            }

            return out.toByteArray();
        }
    }

    public static class ResponseWeRestTemplateInterceptor {

        @RuntimeType
        public static Object intercept(@Origin Method origin,
                                       @SuperCall Callable<?> zuper,
                                       @This Object request) throws Throwable {

            if (LoggerStatusContent.getEnabledProfile()) return zuper.call();
            if (ContextManager.getMessageIdQueueNew().isEmpty()) return zuper.call();

            Object retVal = null;
            Throwable thrown = null;

            String url = null;
            String httpMethod = null;
            Object headers = null;
            String body = null;

            String responseStatus = null;
            Object responseHeaders = null;
            String responseBody = null;

            String serviceCallId = UuidCreator.getTimeBased().toString();

            OffsetDateTime dateStart = OffsetDateTime.now();
            OffsetDateTime dateEnd = null;

            String errorCall = null;

            boolean successLogin = false;

            // -------- request (через интерфейс) --------
            try {
                if (request instanceof BitDiveSpringHttpRequestView) {
                    BitDiveSpringHttpRequestView rq = (BitDiveSpringHttpRequestView) request;

                    url = safeString(rq.bitdiveUriString());
                    httpMethod = safeString(rq.bitdiveMethodString());
                    headers = rq.bitdiveHeaders();

                    Object capturedBody = OutgoingRestTemplateBodyContext.getAndClear();
                    body = (capturedBody != null) ? ReflectionUtils.objectToString(capturedBody) : "";

                    if (getFirstHeader(headers, "x-BitDiv-custom-span-id") == null) {
                        successLogin = true;
                        addHeader(headers, "x-BitDiv-custom-span-id", ContextManager.getSpanId());
                        addHeader(headers, "x-BitDiv-custom-parent-message-id", String.valueOf(ContextManager.getMessageIdQueueNew()));
                        addHeader(headers, "x-BitDiv-custom-service-call-id", serviceCallId);
                    }
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error processing ClientHttpRequest via interface: " + e);
                }
            }

            // -------- execute + finally --------
            try {
                retVal = zuper.call();
                return retVal;
            } catch (Throwable t) {
                thrown = t;
                throw t;
            } finally {
                OutgoingRestTemplateBodyContext.clearSafely();

                // ✅ ВАЖНО: никаких return в finally
                if (!successLogin) {
                    // ничего не отправляем
                } else {
                    dateEnd = OffsetDateTime.now();

                    // -------- response (через интерфейс) --------
                    if (retVal instanceof BitDiveSpringHttpResponseView) {
                        try {
                            BitDiveSpringHttpResponseView resp = (BitDiveSpringHttpResponseView) retVal;

                            responseStatus = String.valueOf(resp.bitdiveStatusCodeValue());
                            responseHeaders = resp.bitdiveHeaders();

                            // форсим кеширование тела
                            try (InputStream ignored = (InputStream) resp.bitdiveGetBody()) {
                                // no-op
                            } catch (Exception ignored) {
                            }

                            byte[] responseBodyBytes = resp.bitdiveCachedBody();
                            Charset charset = parseCharsetFromHeaders(responseHeaders);
                            if (charset == null) charset = Charset.defaultCharset();

                            responseBody = normalizeResponseBodyBytes(responseBodyBytes, responseHeaders, charset);

                        } catch (Exception e) {
                            if (LoggerStatusContent.isErrorsOrDebug()) {
                                System.err.println("Error processing ClientHttpResponse via interface: " + e);
                            }
                        }
                    }

                    if (thrown != null) {
                        errorCall = ReflectionUtils.objectToString(thrown);
                    }

                    if (url != null && !url.contains("eureka/apps")) {
                        sendMessageRequestUrl(
                                UuidCreator.getTimeBased().toString(),
                                ContextManager.getTraceId(),
                                ContextManager.getSpanId(),
                                dateStart,
                                dateEnd,
                                url,
                                httpMethod,
                                ReflectionUtils.objectToString(headers),
                                body,
                                responseStatus,
                                ReflectionUtils.objectToString(responseHeaders),
                                responseBody,
                                errorCall,
                                ContextManager.getMessageIdQueueNew(),
                                serviceCallId
                        );
                    }
                }
            }
        }


        private static String safeString(String s) {
            return s == null ? "" : s;
        }
    }

    // =========================
    // Headers helpers (NO Spring deps)
    // =========================

    @SuppressWarnings("unchecked")
    private static String getFirstHeader(Object headers, String key) {
        if (headers instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) headers;

            Object v = map.get(key);
            if (v == null) {
                for (Map.Entry<Object, Object> e : map.entrySet()) {
                    if (e.getKey() != null && key.equalsIgnoreCase(String.valueOf(e.getKey()))) {
                        v = e.getValue();
                        break;
                    }
                }
            }

            if (v instanceof List) {
                List<?> list = (List<?>) v;
                if (!list.isEmpty() && list.get(0) != null) return String.valueOf(list.get(0));
            } else if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void addHeader(Object headers, String key, String value) {
        if (headers instanceof Map) {
            try {
                Map<Object, Object> map = (Map<Object, Object>) headers;
                Object v = map.get(key);
                List<Object> list;
                if (v instanceof List) {
                    list = (List<Object>) v;
                } else if (v == null) {
                    list = new ArrayList<>(1);
                    map.put(key, list);
                } else {
                    list = new ArrayList<>(2);
                    list.add(v);
                    map.put(key, list);
                }
                list.add(value);
            } catch (Exception ignored) {
                // headers могут быть immutable
            }
        }
    }

    private static Charset parseCharsetFromHeaders(Object headers) {
        String ct = getFirstHeader(headers, "Content-Type");
        if (ct == null) ct = getFirstHeader(headers, "content-type");
        if (ct == null) return null;

        String lower = ct.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx < 0) return null;

        String cs = ct.substring(idx + "charset=".length()).trim();
        int semicolon = cs.indexOf(';');
        if (semicolon >= 0) cs = cs.substring(0, semicolon).trim();
        cs = trimQuotes(cs);

        try {
            return Charset.forName(cs);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2 && ((t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') ||
                (t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\''))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    // =========================
    // ByteBuddy helpers
    // =========================

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type, String name, int argsCount) {
        return findMethodInHierarchy(type, name, argsCount, new HashSet<>());
    }

    /**
     * Finds a method by name/arity walking:
     * - declared methods
     * - interfaces (recursively)
     * - superclass chain
     *
     * Needed because in Spring 6+ some methods can be DEFAULT interface methods.
     */
    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type,
                                                                          String name,
                                                                          int argsCount,
                                                                          Set<String> visited) {
        if (type == null) return null;

        String typeName;
        try {
            typeName = type.getName();
        } catch (Exception e) {
            typeName = null;
        }
        if (typeName != null && !visited.add(typeName)) {
            return null;
        }

        try {
            MethodList<MethodDescription.InDefinedShape> declared =
                    type.getDeclaredMethods().filter(named(name).and(takesArguments(argsCount)));
            if (!declared.isEmpty()) return declared.getOnly();
        } catch (Exception ignored) {
        }

        // interfaces
        try {
            for (TypeDescription.Generic itf : type.getInterfaces()) {
                MethodDescription.InDefinedShape m = findMethodInHierarchy(itf.asErasure(), name, argsCount, visited);
                if (m != null) return m;
            }
        } catch (Exception ignored) {
        }

        // superclass
        try {
            TypeDescription.Generic sc = type.getSuperClass();
            return (sc == null) ? null : findMethodInHierarchy(sc.asErasure(), name, argsCount, visited);
        } catch (Exception ignored) {
            return null;
        }
    }
}