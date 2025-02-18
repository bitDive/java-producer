package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


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
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class org.apache.catalina.connector.CoyoteAdapte");
        }

    }


    public static class CoyoteAdapterAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) Object request) {
            try {
                ContextManager.createNewRequest();
                ContextManager.setUrlStart(extractFullUrlFromRequest(request));
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

                String headersServiceCallId = (String) getHeaderMethod.invoke(request, "x-BitDiv-custom-service-call-id");
                if (headersSpanId != null) {
                    ContextManager.setServiceCallId(headersServiceCallId);
                }


            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("error run service for org.apache.catalina.connector.CoyoteAdapte error: " + e.getMessage());
            }
        }
    }

    public static String extractFullUrlFromRequest(Object request) {
        try {
            String scheme = getStringValue(request, "scheme");
            if (scheme == null) scheme = "http";

            String serverName = getStringValue(request, "serverName");
            if (serverName == null) serverName = "";

            Method getServerPortMethod = request.getClass().getMethod("getServerPort");
            int serverPort = (int) getServerPortMethod.invoke(request);

            Field queryMBField = request.getClass().getDeclaredField("uriMB");
            queryMBField.setAccessible(true);
            Object uriMBVal = queryMBField.get(request);
            Method cloneUrlMethod = uriMBVal.getClass().getMethod("clone");
            cloneUrlMethod.setAccessible(true);
            String uri = cloneUrlMethod.invoke(uriMBVal).toString();
            String queryString = getStringValue(request, "queryString");
            if (queryString == null) queryString = "";

            StringBuilder fullUrl = new StringBuilder();
            fullUrl.append(scheme).append("://").append(serverName);
            if (!(scheme.equals("http") && serverPort == 80) && !(scheme.equals("https") && serverPort == 443)) {
                fullUrl.append(":").append(serverPort);
            }
            fullUrl.append(uri);
            if (!queryString.isEmpty()) {
                fullUrl.append("?").append(queryString);
            }
            return fullUrl.toString();

        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("error get url for response extractFullUrlFromRequest");
            return null;
        }
    }

    private static String getStringValue(Object obj, String methodName) {
        try {
            Method method = obj.getClass().getMethod(methodName);
            Object result = method.invoke(obj);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class getStringValue");
            return null;
        }
    }
}
