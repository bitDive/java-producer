package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;


public class ByteBuddyAgentResponseWeb {
    public static void init() {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(ElementMatchers.named("org.apache.catalina.connector.CoyoteAdapter"))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.visit(Advice.to(CoyoteAdapterAdvice.class)
                                    .on(ElementMatchers.named("service")
                                            .and(ElementMatchers.takesArguments(2))
                                    )
                            )
                    ).installOnByteBuddyAgent();
        } catch (Exception e) {
            if (LoggerStatusContent.isErrors())
                System.err.println("not found class org.apache.catalina.connector.CoyoteAdapte");
        }

    }

    public static class CoyoteAdapterAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) Object request) {
            try {
                ContextManager.createNewRequest();
                Class<?> requestClass = request.getClass();
                java.lang.reflect.Method getHeaderMethod = requestClass.getMethod("getHeader", String.class);
                String headerMessage_Id = (String) getHeaderMethod.invoke(request, "x-BitDiv-custom-parent-message-id");
                if (headerMessage_Id != null) {
                    ContextManager.setParentMessageIdOtherService(headerMessage_Id);
                }
                String headersSpanId = (String) getHeaderMethod.invoke(request, "x-BitDiv-custom-span-id");
                if (headersSpanId != null) {
                    ContextManager.setSpanID(headersSpanId);
                }
            } catch (Exception e) {
                if (LoggerStatusContent.isErrors())
                    System.err.println("error run service for org.apache.catalina.connector.CoyoteAdapte error: " + e.getMessage());
            }
        }
    }
}
