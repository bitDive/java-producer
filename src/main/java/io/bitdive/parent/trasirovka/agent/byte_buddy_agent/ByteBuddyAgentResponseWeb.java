package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.*;


public class ByteBuddyAgentResponseWeb {

    /**
     * View injected into {@code org.apache.coyote.Request} to avoid reflection in advice.
     */
    public interface BitDiveCoyoteRequestView {
        String bitdiveScheme();
        String bitdiveServerName();
        int bitdiveServerPort();
        String bitdiveQueryString();
        String bitdiveRequestUri();
        String bitdiveGetHeader(String name);
        Object bitdiveMimeHeaders();
    }

    /**
     * View injected into {@code org.apache.tomcat.util.http.MimeHeaders}.
     */
    public interface BitDiveMimeHeadersView {
        Enumeration<?> bitdiveNames();
        Enumeration<?> bitdiveValues(String name);
    }

    public static AgentBuilder  init(AgentBuilder agentBuilder) {
        try {
            return agentBuilder
                    // Coyote request view
                    .type(ElementMatchers.named("org.apache.coyote.Request"))
                    .transform((builder, td, cl, module, dd) -> {
                        MethodDescription.InDefinedShape mScheme = findMethodInHierarchy(td, "scheme", 0);
                        MethodDescription.InDefinedShape mServerName = findMethodInHierarchy(td, "serverName", 0);
                        MethodDescription.InDefinedShape mQueryString = findMethodInHierarchy(td, "queryString", 0);
                        MethodDescription.InDefinedShape mServerPort = findMethodInHierarchy(td, "getServerPort", 0);
                        MethodDescription.InDefinedShape mGetHeader = findMethodInHierarchy(td, "getHeader", 1);
                        MethodDescription.InDefinedShape mGetMimeHeaders = findMethodInHierarchy(td, "getMimeHeaders", 0);
                        MethodDescription.InDefinedShape mRequestURI = findMethodInHierarchy(td, "requestURI", 0);

                        MethodDescription.InDefinedShape mToString;
                        try {
                            mToString = new MethodDescription.ForLoadedMethod(Object.class.getMethod("toString"));
                        } catch (NoSuchMethodException e) {
                            return builder;
                        }

                        if (mScheme == null || mServerName == null || mQueryString == null || mServerPort == null ||
                                mGetHeader == null || mGetMimeHeaders == null || mRequestURI == null) {
                            return builder;
                        }

                        return builder
                                .implement(BitDiveCoyoteRequestView.class)
                                .defineMethod("bitdiveScheme", String.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mToString).onMethodCall(MethodCall.invoke(mScheme)))
                                .defineMethod("bitdiveServerName", String.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mToString).onMethodCall(MethodCall.invoke(mServerName)))
                                .defineMethod("bitdiveQueryString", String.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mToString).onMethodCall(MethodCall.invoke(mQueryString)))
                                .defineMethod("bitdiveServerPort", int.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mServerPort))
                                .defineMethod("bitdiveGetHeader", String.class, Visibility.PUBLIC)
                                .withParameters(String.class)
                                .intercept(MethodCall.invoke(mGetHeader).withArgument(0))
                                .defineMethod("bitdiveMimeHeaders", Object.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mGetMimeHeaders))
                                .defineMethod("bitdiveRequestUri", String.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mToString).onMethodCall(MethodCall.invoke(mRequestURI)));
                    })
                    // MimeHeaders view
                    .type(ElementMatchers.named("org.apache.tomcat.util.http.MimeHeaders"))
                    .transform((builder, td, cl, module, dd) -> {
                        MethodDescription.InDefinedShape mNames = findMethodInHierarchy(td, "names", 0);
                        MethodDescription.InDefinedShape mValues = findMethodInHierarchy(td, "values", 1);
                        if (mNames == null || mValues == null) return builder;

                        return builder
                                .implement(BitDiveMimeHeadersView.class)
                                .defineMethod("bitdiveNames", Enumeration.class, Visibility.PUBLIC)
                                .intercept(MethodCall.invoke(mNames))
                                .defineMethod("bitdiveValues", Enumeration.class, Visibility.PUBLIC)
                                .withParameters(String.class)
                                .intercept(MethodCall.invoke(mValues).withArgument(0));
                    })
                    .type(ElementMatchers.named("org.apache.catalina.connector.CoyoteAdapter"))
                    .transform((builder, typeDescription, classLoader, module, dd) ->
                            builder.visit(Advice.to(CoyoteAdapterAdvice.class)
                                    .on(ElementMatchers.named("service")
                                            .and(ElementMatchers.takesArguments(2))
                                    )
                            )
                    );
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("not found class org.apache.catalina.connector.CoyoteAdapte");
        }
        return agentBuilder;

    }


    public static class CoyoteAdapterAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) Object request) {
            if (LoggerStatusContent.getEnabledProfile()) return;
            try {
                ContextManager.createNewRequest();
                RequestBodyCollector.reset();
                ContextManager.setUrlStart(extractFullUrlFromRequest(request));

                if (!(request instanceof BitDiveCoyoteRequestView)) {
                    return;
                }
                BitDiveCoyoteRequestView rq = (BitDiveCoyoteRequestView) request;

                String headerMessage_Id = rq.bitdiveGetHeader("x-BitDiv-custom-parent-message-id");
                if (headerMessage_Id != null) {
                    ContextManager.setParentMessageIdOtherService(headerMessage_Id);
                }

                String headersSpanId = rq.bitdiveGetHeader("x-BitDiv-custom-span-id");
                if (headersSpanId != null) {
                    ContextManager.setSpanID(headersSpanId);
                }

                String headersServiceCallId = rq.bitdiveGetHeader("x-BitDiv-custom-service-call-id");
                if (headersServiceCallId != null) {
                    ContextManager.setServiceCallId(headersServiceCallId);
                }


                // Capture all headers using Coyote API
                try {
                    Map<String, List<String>> headersMap = new LinkedHashMap<>();

                    Object mimeHeaders = rq.bitdiveMimeHeaders();
                    if (mimeHeaders instanceof BitDiveMimeHeadersView) {
                        BitDiveMimeHeadersView mh = (BitDiveMimeHeadersView) mimeHeaders;
                        Enumeration<?> names = mh.bitdiveNames();
                        if (names != null) {
                            while (names.hasMoreElements()) {
                                Object nameObj = names.nextElement();
                                if (nameObj == null) continue;
                                String name = nameObj.toString();

                                // Get values for this header name
                                Enumeration<?> vals = mh.bitdiveValues(name);
                                List<String> values = new ArrayList<>();
                                if (vals != null) {
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
            if (!(request instanceof BitDiveCoyoteRequestView)) return null;
            BitDiveCoyoteRequestView rq = (BitDiveCoyoteRequestView) request;

            String scheme = rq.bitdiveScheme();
            if (scheme == null) scheme = "http";

            String serverName = rq.bitdiveServerName();
            if (serverName == null) serverName = "";

            int serverPort = rq.bitdiveServerPort();

            String uri = rq.bitdiveRequestUri();
            String queryString = rq.bitdiveQueryString();
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

    // =========================
    // ByteBuddy helpers
    // =========================

    private static MethodDescription.InDefinedShape findMethodInHierarchy(TypeDescription type, String name, int argsCount) {
        TypeDescription cur = type;
        while (cur != null) {
            try {
                MethodList<MethodDescription.InDefinedShape> declared =
                        cur.getDeclaredMethods().filter(ElementMatchers.named(name).and(ElementMatchers.takesArguments(argsCount)));
                if (!declared.isEmpty()) return declared.getOnly();
            } catch (Exception ignored) {
            }

            try {
                for (TypeDescription.Generic itf : cur.getInterfaces()) {
                    MethodDescription.InDefinedShape m = findMethodInHierarchy(itf.asErasure(), name, argsCount);
                    if (m != null) return m;
                }
            } catch (Exception ignored) {
            }

            TypeDescription.Generic sc = cur.getSuperClass();
            cur = (sc == null) ? null : sc.asErasure();
        }
        return null;
    }
}

