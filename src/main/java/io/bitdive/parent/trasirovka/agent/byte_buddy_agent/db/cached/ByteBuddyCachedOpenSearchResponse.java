package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

public final class ByteBuddyCachedOpenSearchResponse {

    /**
     * Injected into Apache HttpEntity implementations (HC4/HC5) to avoid reflection when reading content/content-type.
     */
    public interface BitDiveHttpEntityView {
        InputStream bitdiveContent();
        String bitdiveContentTypeValue();
    }

    /**
     * Injected into {@code org.opensearch.client.Response} to avoid reflection in other agents.
     */
    public interface BitDiveOpenSearchResponseView {
        int bitdiveStatusCodeValue();
        Object bitdiveHeaders();

        byte[] bitdiveCachedBody();
        void bitdiveSetCachedBody(byte[] bytes);

        String bitdiveCachedContentType();
        void bitdiveSetCachedContentType(String v);

        Object bitdiveGetEntity();
    }

    public static AgentBuilder init(AgentBuilder agentBuilder)  {
        return agentBuilder
                // Apache HC4 HttpEntity implementations
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("org.apache.http.HttpEntity"))
                        .and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((b, td, cl, m, sd) -> instrumentHttpEntity(b, td))

                // Apache HC5 HttpEntity implementations
                .type(ElementMatchers.hasSuperType(ElementMatchers.named("org.apache.hc.core5.http.HttpEntity"))
                        .and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((b, td, cl, m, sd) -> instrumentHttpEntity(b, td))

                .type(ElementMatchers.named("org.opensearch.client.Response"))
                .transform((b, td, cl, m, sd) -> {
                    MethodDescription.InDefinedShape mGetEntity = findMethodInHierarchy(td, "getEntity", 0);
                    MethodDescription.InDefinedShape mGetStatusLine = findMethodInHierarchy(td, "getStatusLine", 0);
                    MethodDescription.InDefinedShape mGetHeaders = findMethodInHierarchy(td, "getHeaders", 0);

                    b = b
                            .defineField("cachedBody", byte[].class, Visibility.PUBLIC)
                            .defineField("cachedContentType", String.class, Visibility.PUBLIC)
                            .implement(BitDiveOpenSearchResponseView.class)
                            .defineMethod("bitdiveCachedBody", byte[].class, Visibility.PUBLIC)
                            .intercept(FieldAccessor.ofField("cachedBody"))
                            .defineMethod("bitdiveSetCachedBody", void.class, Visibility.PUBLIC)
                            .withParameters(byte[].class)
                            .intercept(FieldAccessor.ofField("cachedBody"))
                            .defineMethod("bitdiveCachedContentType", String.class, Visibility.PUBLIC)
                            .intercept(FieldAccessor.ofField("cachedContentType"))
                            .defineMethod("bitdiveSetCachedContentType", void.class, Visibility.PUBLIC)
                            .withParameters(String.class)
                            .intercept(FieldAccessor.ofField("cachedContentType"));

                    if (mGetEntity != null) {
                        b = b.defineMethod("bitdiveGetEntity", Object.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetEntity));
                    }

                    // status line -> statusCode
                    if (mGetStatusLine != null) {
                        TypeDescription slType = mGetStatusLine.getReturnType().asErasure();
                        MethodDescription.InDefinedShape mGetStatusCode = findMethodInHierarchy(slType, "getStatusCode", 0);
                        if (mGetStatusCode != null) {
                            b = b.defineMethod("bitdiveStatusCodeValue", int.class, Visibility.PUBLIC)
                                    .intercept(
                                            MethodCall.invoke(mGetStatusCode)
                                                    .onMethodCall(MethodCall.invoke(mGetStatusLine))
                                    );
                        }
                    }

                    if (mGetHeaders != null) {
                        b = b.defineMethod("bitdiveHeaders", Object.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetHeaders));
                    } else {
                        // best effort
                        b = b.defineMethod("bitdiveHeaders", Object.class, Visibility.PUBLIC)
                                .intercept(FixedValue.nullValue());
                    }

                    return b
                            .method(ElementMatchers.named("getEntity").and(ElementMatchers.takesArguments(0)))
                            .intercept(MethodDelegation.to(GetEntityInterceptor.class))
                            .method(ElementMatchers.named("getHeader").and(ElementMatchers.takesArguments(1)))
                            .intercept(MethodDelegation.to(GetHeaderInterceptor.class));
                })
                ;
    }

    /*--------------------------------------------------------------*/
    public static class GetHeaderInterceptor {

        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object resp,
                                       @Argument(0) String headerName) throws Exception {

            // Если запрашивают Content-Type и у нас есть кешированное значение, возвращаем его
            if ("Content-Type".equalsIgnoreCase(headerName)) {
                if (resp instanceof BitDiveOpenSearchResponseView) {
                    String cachedContentType = ((BitDiveOpenSearchResponseView) resp).bitdiveCachedContentType();
                    if (cachedContentType != null) return cachedContentType;
                }
            }

            // Обычное поведение для других заголовков
            return zuper.call();
        }
    }

    /*--------------------------------------------------------------*/
    public static class GetEntityInterceptor {

        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object resp) throws Exception {

            if (!(resp instanceof BitDiveOpenSearchResponseView)) {
                return zuper.call();
            }
            BitDiveOpenSearchResponseView view = (BitDiveOpenSearchResponseView) resp;

            byte[] cached = view.bitdiveCachedBody();
            if (cached != null) {
                String contentType = view.bitdiveCachedContentType();
                return newByteArrayEntity(cached, contentType);
            }

            /* --- первый вызов: читаем оригинальный поток --- */
            Object origEntity = zuper.call();              // HttpEntity
            if (origEntity == null) return null;

            // Сохраняем Content-Type заголовок
            String contentType = null;
            if (origEntity instanceof BitDiveHttpEntityView) {
                try {
                    contentType = ((BitDiveHttpEntityView) origEntity).bitdiveContentTypeValue();
                    view.bitdiveSetCachedContentType(contentType);
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error capturing Content-Type: " + e.getMessage());
                    }
                }
            }

            try {
                InputStream in = (origEntity instanceof BitDiveHttpEntityView)
                        ? ((BitDiveHttpEntityView) origEntity).bitdiveContent()
                        : null;
                try (InputStream ignored = in) {
                    if (in != null) {
                        byte[] bytes = readAll(in);

                        // кладём в поле
                        view.bitdiveSetCachedBody(bytes);

                        return newByteArrayEntity(bytes, contentType);
                    }
                } catch (Exception e) {
                    // Если чтение потока не удалось, логируем и возвращаем оригинальный entity
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error reading entity content: " + e.getMessage());
                    }
                    return origEntity;
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error accessing getContent method: " + e.getMessage());
                }
                return origEntity;
            }

            // Если дошли до сюда, значит getContent вернул null
            return origEntity;
        }

        /* ---- helpers ------------------------------------ */

        public static byte[] readAll(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }

        // Создаем ByteArrayEntity с Content-Type напрямую из строки
        public static Object newByteArrayEntity(byte[] bytes, String contentTypeStr) throws Exception {
            Object ct = null;
            if (contentTypeStr != null) {
                try {
                    Class<?> ct4 = Class.forName("org.apache.http.entity.ContentType");
                    ct = ct4.getMethod("parse", String.class).invoke(null, contentTypeStr);
                } catch (ClassNotFoundException ignore) {
                    try {
                        Class<?> ct5 = Class.forName("org.apache.hc.core5.http.ContentType");
                        ct = ct5.getMethod("parse", String.class).invoke(null, contentTypeStr);
                    } catch (Exception e) {
                        // Если не удалось получить ContentType, попробуем создать без него
                    }
                }
            }

            try {   // HC4
                Class<?> bae4 = Class.forName("org.apache.http.entity.ByteArrayEntity");
                if (ct != null) {
                    Constructor<?> c = bae4.getConstructor(byte[].class, ct.getClass());
                    return c.newInstance(bytes, ct);
                }
                return bae4.getConstructor(byte[].class).newInstance(bytes);
            } catch (ClassNotFoundException ignore) {
                Class<?> bae5 = Class.forName("org.apache.hc.core5.http.io.entity.ByteArrayEntity");
                if (ct != null) {
                    Constructor<?> c = bae5.getConstructor(byte[].class, ct.getClass());
                    return c.newInstance(bytes, ct);
                }
                return bae5.getConstructor(byte[].class).newInstance(bytes);
            }
        }
    }

    // =========================
    // ByteBuddy helpers
    // =========================

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type, String name, int argsCount) {
        return findMethodInHierarchy(type, name, argsCount, new HashSet<String>());
    }

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
        if (typeName != null && !visited.add(typeName)) return null;

        try {
            MethodList<MethodDescription.InDefinedShape> declared =
                    type.getDeclaredMethods().filter(ElementMatchers.named(name).and(ElementMatchers.takesArguments(argsCount)));
            if (!declared.isEmpty()) return declared.getOnly();
        } catch (Exception ignored) {
        }

        try {
            for (TypeDescription.Generic itf : type.getInterfaces()) {
                MethodDescription.InDefinedShape m = findMethodInHierarchy(itf.asErasure(), name, argsCount, visited);
                if (m != null) return m;
            }
        } catch (Exception ignored) {
        }

        try {
            TypeDescription.Generic sc = type.getSuperClass();
            return (sc == null) ? null : findMethodInHierarchy(sc.asErasure(), name, argsCount, visited);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static net.bytebuddy.dynamic.DynamicType.Builder<?> instrumentHttpEntity(net.bytebuddy.dynamic.DynamicType.Builder<?> b,
                                                                                    TypeDescription td) {
        try {
            MethodDescription.InDefinedShape mGetContent = findMethodInHierarchy(td, "getContent", 0);
            MethodDescription.InDefinedShape mGetContentType = findMethodInHierarchy(td, "getContentType", 0);
            if (mGetContent == null || mGetContentType == null) return b;

            TypeDescription headerType = mGetContentType.getReturnType().asErasure();
            MethodDescription.InDefinedShape mGetValue = findMethodInHierarchy(headerType, "getValue", 0);
            if (mGetValue == null) return b;

            return b
                    .implement(BitDiveHttpEntityView.class)
                    .defineMethod("bitdiveContent", InputStream.class, Visibility.PUBLIC)
                    .intercept(MethodCall.invoke(mGetContent))
                    .defineMethod("bitdiveContentTypeValue", String.class, Visibility.PUBLIC)
                    .intercept(
                            MethodCall.invoke(mGetValue)
                                    .onMethodCall(MethodCall.invoke(mGetContentType))
                    );
        } catch (Exception e) {
            return b;
        }
    }
}
