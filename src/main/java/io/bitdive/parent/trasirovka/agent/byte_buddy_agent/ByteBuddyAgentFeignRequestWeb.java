package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static io.bitdive.parent.message_producer.MessageService.sendMessageRequestUrl;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;

public class ByteBuddyAgentFeignRequestWeb {
    public static void init() {
        try {
            Class<?> clientClass = Class.forName("feign.Client");
            new AgentBuilder.Default()
                    .type(ElementMatchers.isSubTypeOf(clientClass)
                            .and(ElementMatchers.not(ElementMatchers.nameContains("loadbalancer")))
                    )
                    .transform((builder, typeDescription, classLoader, module, sd) ->
                            builder.method(ElementMatchers.named("execute"))
                                    .intercept(MethodDelegation.to(FeignClientInterceptor.class))
                    ).installOnByteBuddyAgent();
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Not found class feign.Client in ClassLoader.");
        }
    }

    public static class FeignClientInterceptor {

        @RuntimeType
        public static Object intercept(@SuperCall Callable<Object> zuper,
                                       @SuperMethod Method superMethod,
                                       @This Object proxy,
                                       @AllArguments Object args[]) throws Throwable {
            if (ContextManager.getMessageIdQueueNew().isEmpty()) return zuper.call();
            Object retVal = null;
            Throwable thrown = null;

            OffsetDateTime dateStart = OffsetDateTime.now();
            OffsetDateTime dateEnd = null;

            // Trace data variables
            String url = null;
            String httpMethod = null;
            Map<String, Collection<String>> headers = null;
            byte[] body = null;
            Charset charset = null;

            String responseStatus = null;
            Map<String, Collection<String>> responseHeaders = null;
            String responseBody = null;

            String errorCall = null;
            String serviceCallId = UuidCreator.getTimeBased().toString();


            try {
                Object request = args[0]; // The first argument is the request object

                Class<?> requestClass = request.getClass();

                // Extract request details for tracing
                Method urlMethod = requestClass.getMethod("url");
                Method httpMethodMethod = requestClass.getMethod("httpMethod");
                Method headersMethod = requestClass.getMethod("headers");
                Method bodyMethod = requestClass.getMethod("body");
                Method charsetMethod = requestClass.getMethod("charset");
                Method requestTemplateMethod = requestClass.getMethod("requestTemplate");

                url = (String) urlMethod.invoke(request);
                Object httpMethodEnum = httpMethodMethod.invoke(request);
                httpMethod = httpMethodEnum.toString();
                headers = new HashMap<>((Map<String, Collection<String>>) headersMethod.invoke(request));
                body = (byte[]) bodyMethod.invoke(request);
                charset = (Charset) charsetMethod.invoke(request);
                Object requestTemplate = requestTemplateMethod.invoke(request);

                // Modify the headers by adding custom headers
                headers.put("x-BitDiv-custom-span-id", Collections.singletonList(ContextManager.getSpanId()));
                headers.put("x-BitDiv-custom-parent-message-id", Collections.singletonList(ContextManager.getMessageIdQueueNew()));
                headers.put("x-BitDiv-custom-service-call-id", Collections.singletonList(serviceCallId));

                // Recreate the request with modified headers
                // Get the create method
                Method requestCreateMethod = requestClass.getMethod("create",
                        httpMethodEnum.getClass(), // HttpMethod enum class
                        String.class,
                        Map.class,
                        byte[].class,
                        Charset.class,
                        requestTemplate.getClass());

                Object newRequest = requestCreateMethod.invoke(null,
                        httpMethodEnum, // HttpMethod
                        url,
                        headers,
                        body,
                        charset,
                        requestTemplate);

                // Replace the request argument with the new request
                args[0] = newRequest;
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("Error modifying Feign Request: " + e.getMessage());
                }
            }

            try {
                // Proceed with the original method call
                retVal = superMethod.invoke(proxy, args);
            } catch (InvocationTargetException t) {
                thrown = t.getCause();
                throw t.getCause();
            } finally {
                dateEnd = OffsetDateTime.now();

                try {
                    // Extract response details if available
                    if (retVal != null) {
                        Class<?> responseClass = retVal.getClass();

                        Method statusMethod = responseClass.getMethod("status");
                        Method responseHeadersMethod = responseClass.getMethod("headers");
                        Method responseBodyMethod = responseClass.getMethod("body");

                        responseStatus = String.valueOf(statusMethod.invoke(retVal));
                        responseHeaders = (Map<String, Collection<String>>) responseHeadersMethod.invoke(retVal);
                        Object responseBodyObject = responseBodyMethod.invoke(retVal);

                        // Handle response body via reflection
                        if (responseBodyObject != null) {
                            Class<?> responseBodyClass = responseBodyObject.getClass();

                            // Obtain the InputStream from the response body
                            Method asInputStreamMethod = responseBodyClass.getMethod("asInputStream");
                            asInputStreamMethod.setAccessible(true);
                            InputStream inputStream = (InputStream) asInputStreamMethod.invoke(responseBodyObject);

                            // Read the InputStream into a byte array
                            byte[] responseBodyBytes = IOUtils.toByteArray(inputStream);

                            // Close the original InputStream
                            inputStream.close();

                            // Convert the byte array to a String for tracing
                            Charset responseCharset = getResponseCharset(responseHeaders);
                            if (responseCharset == null) {
                                responseCharset = Charset.defaultCharset();
                            }
                            responseBody = new String(responseBodyBytes, responseCharset);

                            // Create a new response object with the new body
                            Method toBuilderMethod = responseClass.getMethod("toBuilder");
                            Object responseBuilder = toBuilderMethod.invoke(retVal);

                            // Set the new body on the builder using body(byte[]) method
                            Method bodyMethod = responseBuilder.getClass().getMethod("body", byte[].class);
                            bodyMethod.invoke(responseBuilder, (Object) responseBodyBytes);

                            // Build the new response
                            Method buildMethod = responseBuilder.getClass().getMethod("build");
                            Object newResponse = buildMethod.invoke(responseBuilder);

                            // Replace retVal with newResponse
                            retVal = newResponse;
                        }
                    }

                    if (thrown != null) {
                        errorCall = getaNullThrowable(thrown);
                    }

                    // Call sendMessageRequestUrl with the collected data
                    sendMessageRequestUrl(
                            UuidCreator.getTimeBased().toString(),
                            ContextManager.getTraceId(),
                            ContextManager.getSpanId(),
                            dateStart,
                            dateEnd,
                            url,
                            httpMethod,
                            ReflectionUtils.objectToString(headers),
                            body != null ? new String(body, charset != null ? charset : Charset.defaultCharset()) : null,
                            responseStatus,
                            ReflectionUtils.objectToString(responseHeaders),
                            responseBody,
                            errorCall,
                            ContextManager.getMessageIdQueueNew(),
                            serviceCallId
                    );
                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("Error processing Feign Request/Response: " + e.getMessage());
                    }
                }
            }

            return retVal;
        }

        // Helper method to get the response charset from headers
        private static Charset getResponseCharset(Map<String, Collection<String>> headers) {
            if (headers != null) {
                Collection<String> contentTypeHeaders = headers.get("Content-Type");
                if (contentTypeHeaders != null) {
                    for (String contentType : contentTypeHeaders) {
                        String[] parts = contentType.split(";");
                        for (String part : parts) {
                            if (part.trim().toLowerCase().startsWith("charset=")) {
                                String charsetName = part.trim().substring(8);
                                try {
                                    return Charset.forName(charsetName);
                                } catch (Exception e) {
                                    // Ignore and continue
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

}
