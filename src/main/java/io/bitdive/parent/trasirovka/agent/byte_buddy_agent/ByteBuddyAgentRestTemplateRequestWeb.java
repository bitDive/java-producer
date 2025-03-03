package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Callable;

import static io.bitdive.parent.message_producer.MessageService.sendMessageRequestUrl;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;

public class ByteBuddyAgentRestTemplateRequestWeb {
    public static void init() {
        try {
            Class<?> clientHttpRequestClass = Class.forName("org.springframework.http.client.ClientHttpRequest");

            new AgentBuilder.Default()
                    .type(ElementMatchers.isSubTypeOf(clientHttpRequestClass))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.method(ElementMatchers.named("execute"))
                                    .intercept(MethodDelegation.to(ResponseWeRestTemplateInterceptor.class))
                    ).installOnByteBuddyAgent();
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Not found class org.springframework.http.client.ClientHttpRequest in ClassLoader.");
        }
    }

    public static class ResponseWeRestTemplateInterceptor {

        @RuntimeType
        public static Object intercept(@Origin Method method,
                                       @SuperCall Callable<?> zuper,
                                       @This Object request) throws Throwable {
            if (ContextManager.getMessageIdQueueNew().isEmpty()) return zuper.call();
            Object retVal = null;
            Throwable thrown = null;

            String uri = null;
            String httpMethod = null;
            Object headers = null;
            Object body = null;

            String responseStatus = null;
            Object responseHeaders = null;
            String responseBody = null;
            Charset charset = null;

            String serviceCallId = UuidCreator.getTimeBased().toString();

            OffsetDateTime dateStart = OffsetDateTime.now();
            OffsetDateTime dateEnd = null;

            String errorCall = null;

            try {
                if (request.getClass().getName().contains("org.springframework.http.client")) {
                    Method getUriMethod = request.getClass().getMethod("getURI");
                    getUriMethod.setAccessible(true);
                    uri = Optional.ofNullable(getUriMethod.invoke(request)).map(Object::toString).orElse("");

                    Method getMethodMethod = request.getClass().getMethod("getMethod");
                    getMethodMethod.setAccessible(true);
                    httpMethod = Optional.ofNullable(getMethodMethod.invoke(request)).map(Object::toString).orElse("");

                    Method getHeadersMethod = request.getClass().getMethod("getHeaders");
                    getHeadersMethod.setAccessible(true);
                    headers = getHeadersMethod.invoke(request);

                    Method getBodyMethod = null;
                    try {
                        getBodyMethod = request.getClass().getMethod("getBody");
                    } catch (Exception e) {
                        getBodyMethod = request.getClass().getDeclaredMethod("getBody");
                    }
                    try {
                        getBodyMethod.setAccessible(true);
                        body = Optional.ofNullable(getBodyMethod.invoke(request)).map(Object::toString).orElse("");
                    } catch (Exception e) {

                    }
                    Method addHeaderMethod = headers.getClass().getMethod("add", String.class, String.class);
                    addHeaderMethod.invoke(headers, "x-BitDiv-custom-span-id", ContextManager.getSpanId());
                    addHeaderMethod.invoke(headers, "x-BitDiv-custom-parent-message-id", ContextManager.getMessageIdQueueNew());
                    addHeaderMethod.invoke(headers, "x-BitDiv-custom-service-call-id", serviceCallId);
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error processing ClientHttpRequest: " + e);
                }
            }

            try {
                retVal = zuper.call();
            } catch (InvocationTargetException t) {
                thrown = t.getCause();
                throw t.getCause();
            } finally {
                dateEnd = OffsetDateTime.now();

                if (retVal != null) {
                    try {
                        String responseClassName = retVal.getClass().getName();
                        if (responseClassName.contains("org.springframework.http")) {

                            Method getStatusCodeMethod = retVal.getClass().getMethod("getStatusCode");
                            getStatusCodeMethod.setAccessible(true);
                            Object statusCode = getStatusCodeMethod.invoke(retVal);

                            Method valueMethod = statusCode.getClass().getMethod("value");
                            valueMethod.setAccessible(true);
                            Object statusCodeValue = valueMethod.invoke(statusCode);
                            responseStatus = statusCodeValue.toString();

                            Method getHeadersMethod = retVal.getClass().getMethod("getHeaders");
                            getHeadersMethod.setAccessible(true);
                            responseHeaders = getHeadersMethod.invoke(retVal);

                            Method getBodyMethod = retVal.getClass().getMethod("getBody");
                            getBodyMethod.setAccessible(true);
                            InputStream originalInputStream = (InputStream) getBodyMethod.invoke(retVal);

                            Field cachedBodyField = retVal.getClass().getDeclaredField("cachedBody");
                            cachedBodyField.setAccessible(true);
                            byte[] responseBodyBytes = (byte[]) cachedBodyField.get(retVal);

                            // Convert the byte array to a String for logging/tracing
                            Charset responseCharset = getResponseCharset(responseHeaders);
                            if (responseCharset == null) {
                                responseCharset = Charset.defaultCharset();
                            }
                            responseBody = new String(responseBodyBytes, responseCharset);

                        }
                    } catch (Exception e) {
                        if (LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("Error processing ClientHttpResponse: " + e.getMessage());
                        }
                    }
                }

                if (thrown != null) {
                    errorCall = getaNullThrowable(thrown);
                }

                sendMessageRequestUrl(
                        UuidCreator.getTimeBased().toString(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        dateStart,
                        dateEnd,
                        uri,
                        httpMethod,
                        ReflectionUtils.objectToString(headers),
                        ReflectionUtils.objectToString(body),
                        responseStatus,
                        ReflectionUtils.objectToString(responseHeaders),
                        responseBody,
                        errorCall,
                        ContextManager.getMessageIdQueueNew(),
                        serviceCallId
                );
            }

            return retVal;
        }

        // Helper method to read InputStream into byte array
        private static byte[] readAllBytes(InputStream inputStream) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        }

        // Helper method to get the response charset from headers
        private static Charset getResponseCharset(Object headers) {
            try {
                Method getContentTypeMethod = headers.getClass().getMethod("getContentType");
                getContentTypeMethod.setAccessible(true);
                Object mediaType = getContentTypeMethod.invoke(headers);
                if (mediaType != null) {
                    Method getCharsetMethod = mediaType.getClass().getMethod("getCharset");
                    getCharsetMethod.setAccessible(true);
                    Charset charset = (Charset) getCharsetMethod.invoke(mediaType);
                    return charset;
                }
            } catch (Exception e) {
                // Ignore and use default charset
            }
            return null;
        }

        // Helper method to replace the response body's InputStream
        private static void replaceResponseBodyInputStream(Object response, InputStream newInputStream) throws Exception {
            // The response object is an instance of ClientHttpResponse
            // Depending on the implementation, we need to set the InputStream

            // For example, if it's a SimpleClientHttpResponse
            Field bodyField = null;
            Class<?> responseClass = response.getClass();
            while (responseClass != null) {
                try {
                    bodyField = responseClass.getDeclaredField("responseStream");
                    bodyField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException e) {
                    responseClass = responseClass.getSuperclass();
                }
            }

            if (bodyField != null) {
                bodyField.set(response, newInputStream);
            } else {
                // If we cannot find the 'body' field, we may need to wrap the response
                // Alternatively, you can throw an exception or log an error
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Unable to replace response body InputStream.");
                }
            }
        }
    }
}
