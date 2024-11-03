package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

public class ByteBuddyAgentRestTemplateRequestWeb {
    public static void init() {
        try {
            Class<?> clientHttpRequestClass = Class.forName("org.springframework.http.client.ClientHttpRequest");

            new AgentBuilder.Default()
                    .type(ElementMatchers.isSubTypeOf(clientHttpRequestClass))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.method(ElementMatchers.named("execute"))
                                    .intercept(MethodDelegation.to(ResponseWeRestTemplatebInterceptor.class))
                    ).installOnByteBuddyAgent();
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class org.springframework.http.client.ClientHttpRequest in ClassLoader.");
        }
    }

    public static class ResponseWeRestTemplatebInterceptor {
        @RuntimeType
        public static Object intercept(@Origin Method method,
                                       @SuperCall Callable<?> zuper,
                                       @This Object request) throws Exception {

        /*System.out.println(request.getURI());
        System.out.println(request.getMethod());
        System.out.println(request.getHeaders());
        System.out.println(request.getBody());*/
            try {
                if (request.getClass().getName().contains("org.springframework.http.client")) {
                    Method getHeadersMethod = request.getClass().getMethod("getHeaders");
                    Object headers = getHeadersMethod.invoke(request);

                    // Добавляем заголовок через рефлексию
                    Method addHeaderMethod = headers.getClass().getMethod("add", String.class, String.class);
                    addHeaderMethod.invoke(headers, "x-BitDiv-custom-span-id", ContextManager.getSpanId());
                    addHeaderMethod.invoke(headers, "x-BitDiv-custom-parent-message-id", ContextManager.getMessageIdQueueNew());

                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("error run org.springframework.http.client.ClientHttpRequest error: " + e.getMessage());
            }

            return zuper.call();
        }

    }

}
