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

/**
 * ByteBuddy agent for caching OpenSearch request bodies and content types.
 * This agent intercepts the OpenSearch client's Request class to cache the entity body
 * and Content-Type header for repeated access, improving performance by avoiding
 * multiple reads of the input stream.
 */
public final class ByteBuddyCachedOpenSearchReqest {

    /**
     * Initializes the ByteBuddy agent for OpenSearch request caching.
     * Transforms the org.opensearch.client.Request class to add caching fields
     * and intercept getEntity() and getHeader() methods.
     *
     * @param inst the instrumentation instance
     * @return the resettable class file transformer
     */
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
    /**
     * Interceptor for the getHeader method to return cached Content-Type if available.
     */
    public static class GetHeaderInterceptor {

        /**
         * Intercepts calls to getHeader method.
         * If requesting Content-Type and it's cached, returns the cached value.
         * Otherwise, proceeds with the original method.
         *
         * @param zuper the original method call
         * @param resp the request object
         * @param headerName the header name being requested
         * @return the header value
         * @throws Exception if an error occurs
         */
        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object resp,
                                       @Argument(0) String headerName) throws Exception {

            // If requesting Content-Type and we have a cached value, return it
            if ("Content-Type".equalsIgnoreCase(headerName)) {
                try {
                    String cachedContentType = (String) resp.getClass()
                            .getDeclaredField("cachedContentType")
                            .get(resp);
                    if (cachedContentType != null) {
                        return cachedContentType;
                    }
                } catch (Exception e) {
                    // Ignore and proceed to original method
                }
            }

            // Default behavior for other headers
            return zuper.call();
        }
    }

    /*--------------------------------------------------------------*/
    /**
     * Interceptor for the getEntity method to cache and return the entity body.
     * On first call, reads the input stream, caches the bytes and content type,
     * and returns a new ByteArrayEntity. Subsequent calls return the cached data.
     */
    public static class GetEntityInterceptor {

        /**
         * Intercepts calls to getEntity method.
         * Returns cached entity if available, otherwise reads and caches the original entity.
         *
         * @param zuper the original method call
         * @param resp the request object
         * @return the entity object
         * @throws Exception if an error occurs
         */
        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @This Object resp) throws Exception {

            // Check if we have cached data
            byte[] cached = (byte[]) resp.getClass()
                    .getDeclaredField("cachedBody")
                    .get(resp);
            if (cached != null) {
                String contentType = (String) resp.getClass()
                        .getDeclaredField("cachedContentType")
                        .get(resp);
                return newByteArrayEntity(cached, contentType);
            }

            /* --- First call: read the original stream --- */
            Object origEntity = zuper.call();              // HttpEntity
            if (origEntity == null) return null;

            // Cache the Content-Type header
            String contentType = null;
            try {
                // Get Content-Type from the original Entity
                Method ctMethod = origEntity.getClass().getMethod("getContentType");
                Object headerObj = ctMethod.invoke(origEntity);
                if (headerObj != null) {
                    Method valMethod = headerObj.getClass().getMethod("getValue");
                    contentType = (String) valMethod.invoke(headerObj);

                    // Store Content-Type in the field
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

                        // Store in the field
                        resp.getClass().getDeclaredField("cachedBody")
                                .set(resp, bytes);

                        return newByteArrayEntity(bytes, contentType);
                    }
                } catch (Exception e) {
                    // If reading the stream failed, log and return original entity
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

            // If we reach here, getContent returned null
            return origEntity;
        }

        /* ---- Helper methods ------------------------------------ */

        /**
         * Reads all bytes from an InputStream.
         *
         * @param in the input stream
         * @return the byte array
         * @throws Exception if an error occurs
         */
        public static byte[] readAll(InputStream in) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }

        /**
         * Creates a new ByteArrayEntity with the given bytes and content type.
         * Handles both Apache HttpClient 4 and 5 versions.
         *
         * @param bytes the byte array
         * @param contentTypeStr the content type string
         * @return the ByteArrayEntity instance
         * @throws Exception if creation fails
         */
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
                        // If unable to get ContentType, try to create without it
                    }
                }
            }

            try {   // HttpClient 4
                Class<?> bae4 = Class.forName("org.apache.http.entity.ByteArrayEntity");
                if (ct != null) {
                    Constructor<?> c = bae4.getConstructor(byte[].class, ct.getClass());
                    return c.newInstance(bytes, ct);
                }
                return bae4.getConstructor(byte[].class).newInstance(bytes);
            } catch (ClassNotFoundException ignore) {
                // HttpClient 5
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
