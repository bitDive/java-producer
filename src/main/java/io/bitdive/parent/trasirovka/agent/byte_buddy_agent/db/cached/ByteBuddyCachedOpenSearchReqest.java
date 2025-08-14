package io.bitdive.parent.trasirovka.agent.byte_buddy_agent.db.cached;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public final class ByteBuddyCachedOpenSearchReqest {

    public static ResettableClassFileTransformer init(Instrumentation inst) {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named("org.opensearch.client.Request"))
                .transform((b, td, cl, m, sd) ->
                        b.defineField("cachedBody", byte[].class, Visibility.PUBLIC)
                                .defineField("cachedContentType", String.class, Visibility.PUBLIC)
                                .method(ElementMatchers.named("getEntity").and(ElementMatchers.takesArguments(0)))
                                .intercept(MethodDelegation.to(GetEntityInterceptor.class))
                                .method(ElementMatchers.named("getHeader").and(ElementMatchers.takesArguments(1)))
                                .intercept(MethodDelegation.to(GetHeaderInterceptor.class)))
                .installOn(inst);
    }

    /*--------------------------------------------------------------*/
    public static class GetHeaderInterceptor {

        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object resp,
                                       @Argument(0) String headerName) throws Exception {

            // Если запрашивают Content-Type и у нас есть кешированное значение, возвращаем его
            if ("Content-Type".equalsIgnoreCase(headerName)) {
                try {
                    String cachedContentType = (String) resp.getClass()
                            .getDeclaredField("cachedContentType")
                            .get(resp);
                    if (cachedContentType != null) {
                        return cachedContentType;
                    }
                } catch (Exception e) {
                    // Игнорируем и просто продолжаем к оригинальному методу
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

            byte[] cached = (byte[]) resp.getClass()
                    .getDeclaredField("cachedBody")
                    .get(resp);
            if (cached != null) {
                String contentType = (String) resp.getClass()
                        .getDeclaredField("cachedContentType")
                        .get(resp);
                return newByteArrayEntity(cached, contentType);
            }

            /* --- первый вызов: читаем оригинальный поток --- */
            Object origEntity = zuper.call();              // HttpEntity
            if (origEntity == null) return null;

            // Сохраняем Content-Type заголовок
            String contentType = null;
            try {
                // Получаем Content-Type из оригинального Entity
                Method ctMethod = origEntity.getClass().getMethod("getContentType");
                Object headerObj = ctMethod.invoke(origEntity);
                if (headerObj != null) {
                    Method valMethod = headerObj.getClass().getMethod("getValue");
                    contentType = (String) valMethod.invoke(headerObj);

                    // Сохраняем Content-Type в поле
                    resp.getClass().getDeclaredField("cachedContentType")
                            .set(resp, contentType);
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error capturing Content-Type: " + e.getMessage());
                }
            }

            try {
                Method getContent = origEntity.getClass().getMethod("getContent");
                getContent.setAccessible(true);
                try (InputStream in = (InputStream) getContent.invoke(origEntity)) {
                    if (in != null) {
                        byte[] bytes = readAll(in);

                        // кладём в поле
                        resp.getClass().getDeclaredField("cachedBody")
                                .set(resp, bytes);

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
}
