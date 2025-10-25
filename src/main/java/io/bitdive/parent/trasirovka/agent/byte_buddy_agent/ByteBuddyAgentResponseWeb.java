package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;


public class ByteBuddyAgentResponseWeb {
    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        try {
            return new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(ElementMatchers.named("org.apache.catalina.connector.CoyoteAdapter"))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.visit(Advice.to(CoyoteAdapterAdvice.class)
                                    .on(ElementMatchers.named("service")
                                            .and(ElementMatchers.takesArguments(2))
                                    )
                            )
                    ).installOn(instrumentation);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class org.apache.catalina.connector.CoyoteAdapte");
        }
        return null;

    }


    public static class CoyoteAdapterAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) Object request) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                ContextManager.createNewRequest();
                RequestBodyCollector.reset();
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
                if (headersServiceCallId != null) {
                    ContextManager.setServiceCallId(headersServiceCallId);
                }


                // Capture all headers using Coyote API
                try {
                    Method getMimeHeaders = requestClass.getMethod("getMimeHeaders");
                    Object mimeHeaders = getMimeHeaders.invoke(request);
                    Map<String, List<String>> headersMap = new LinkedHashMap<>();

                    if (mimeHeaders != null) {
                        Class<?> mimeHeadersClass = mimeHeaders.getClass();
                        Method namesMethod = mimeHeadersClass.getMethod("names");
                        Object namesEnumObj = namesMethod.invoke(mimeHeaders);

                        if (namesEnumObj instanceof Enumeration) {
                            Enumeration<?> names = (Enumeration<?>) namesEnumObj;
                            while (names.hasMoreElements()) {
                                Object nameObj = names.nextElement();
                                if (nameObj == null) continue;
                                String name = nameObj.toString();

                                // Get values for this header name
                                Method getValuesMethod = mimeHeadersClass.getMethod("values", String.class);
                                Object valuesEnumObj = getValuesMethod.invoke(mimeHeaders, name);
                                List<String> values = new ArrayList<>();
                                if (valuesEnumObj instanceof Enumeration) {
                                    Enumeration<?> vals = (Enumeration<?>) valuesEnumObj;
                                    while (vals.hasMoreElements()) {
                                        Object v = vals.nextElement();
                                        if (v != null) values.add(v.toString());
                                    }
                                }
                                headersMap.put(name, values);
                            }
                        }
                    }
                    ContextManager.setRequestHeaders(headersMap);
                } catch (Exception ignored) {
                    if (LoggerStatusContent.isErrorsOrDebug())
                        System.err.println("Error capturing headers: " + ignored.getMessage());
                }

            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("error run service for org.apache.catalina.connector.CoyoteAdapte error: " + e.getMessage());
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            if (LoggerStatusContent.getEnabledProfile()) return;
            // Note: Body is collected during method execution by ByteBuddyAgentCoyoteInputStream
            // and must be retrieved here AFTER the controller has finished
            // However, for incoming requests we need it BEFORE the controller runs,
            // so we'll handle this differently - via a separate interception point
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

