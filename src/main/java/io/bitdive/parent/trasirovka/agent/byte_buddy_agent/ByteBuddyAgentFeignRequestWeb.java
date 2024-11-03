package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ByteBuddyAgentFeignRequestWeb {
    public static void init() {
        try {
            Class<?> clientClass = Class.forName("feign.Client");
            new AgentBuilder.Default()
                    .type(ElementMatchers.isSubTypeOf(clientClass))
                    .transform((builder, typeDescription, classLoader, module, sd) ->
                            builder.visit(Advice.to(TestMyForClient.class).on(ElementMatchers.named("execute")))
                    ).installOnByteBuddyAgent();
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class feign.Client in ClassLoader");
        }
    }

    public static class TestMyForClient {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void intercept(@Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request) throws Exception {
            try {
                Class<?> requestClass = request.getClass();

                Method headersMethod = requestClass.getMethod("headers");
                Method httpMethodMethod = requestClass.getMethod("httpMethod");
                Method urlMethod = requestClass.getMethod("url");
                Method bodyMethod = requestClass.getMethod("body");
                Method charsetMethod = requestClass.getMethod("charset");
                Method requestTemplateMethod = requestClass.getMethod("requestTemplate");

                // Get existing headers
                Map<String, Collection<String>> headers = new HashMap<>(
                        (Map<String, Collection<String>>) headersMethod.invoke(request)
                );

                headers.put("x-BitDiv-custom-span-id", Collections.singletonList(ContextManager.getSpanId()));
                headers.put("x-BitDiv-custom-parent-message-id", Collections.singletonList(ContextManager.getMessageIdQueueNew()));

                Object httpMethod = httpMethodMethod.invoke(request);
                String url = (String) urlMethod.invoke(request);
                byte[] body = (byte[]) bodyMethod.invoke(request);
                Charset charset = (Charset) charsetMethod.invoke(request);
                Object requestTemplate = requestTemplateMethod.invoke(request);

                Method createMethod = requestClass.getMethod("create",
                        httpMethod.getClass(), String.class, Map.class, byte[].class, Charset.class, requestTemplate.getClass()
                );

                Object newRequest = createMethod.invoke(null, httpMethod, url, headers, body, charset, requestTemplate);

                request = newRequest;
            } catch (Exception e) {
                if (LoggerStatusContent.isErrors())
                    System.err.println("error call execute for feign.Client error: " + e.getMessage());
            }
        }
    }
}
