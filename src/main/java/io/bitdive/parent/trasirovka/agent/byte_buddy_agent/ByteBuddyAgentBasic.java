package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.anotations.MonitoringClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.trasirovka.agent.utils.RequestBodyCollector;
import io.bitdive.parent.utils.MethodTypeEnum;
import io.bitdive.parent.utils.Pair;
import io.bitdive.parent.utils.UtilsDataConvert;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static io.bitdive.parent.message_producer.MessageService.sendMessageEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.*;
import static io.bitdive.parent.utils.UtilsDataConvert.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class ByteBuddyAgentBasic {

    public static ElementMatcher.Junction<TypeDescription> getSpringComponentMatcher() {
        boolean monitoringOnlySpringComponent = YamlParserConfig.getProfilingConfig()
                .getMonitoring()
                .getMonitoringOnlySpringComponent();

        if (monitoringOnlySpringComponent) {
            return ElementMatchers.isAnnotatedWith(nameContains("org.springframework"))
                    .or(ElementMatchers.isAnnotatedWith(MonitoringClass.class));
        } else {
            return ElementMatchers.any();
        }
    }

    public static <T extends NamedElement> ElementMatcher.Junction<T> getApplicationPackedScanner(String[] infixes) {
        ElementMatcher.Junction<T> matcher = none();
        for (String infix : infixes) {
            matcher = matcher.or(nameStartsWith(infix));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<TypeDescription> getExcludedSuperTypeMatcher() {
        String[] excludedPackages = {
                "org.springframework.security.",
                "org.springframework.web.filter."
        };

        ElementMatcher.Junction<TypeDescription> matcher = none();
        for (String pkg : excludedPackages) {
            matcher = matcher.or(ElementMatchers.hasSuperType(ElementMatchers.nameStartsWith(pkg)));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<TypeDescription> getWebSocketHandlerExclusion() {
        return not(hasSuperType(named("org.springframework.web.socket.WebSocketHandler")))
                .and(not(hasSuperType(named("org.springframework.web.socket.handler.AbstractWebSocketHandler"))))
                .and(not(hasSuperType(named("org.springframework.web.socket.handler.TextWebSocketHandler"))));
    }

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        ElementMatcher.Junction<TypeDescription> monitoredTypes =
                getApplicationPackedScanner(
                        YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner())
                        .and(getSpringComponentMatcher())
                        .and(getWebSocketHandlerExclusion())
                        .and(not(isEnum()))
                        .and(not(isAnnotatedWith(named("io.bitdive.parent.anotations.NotMonitoring"))))
                        .and(not(isAnnotatedWith(nameContains("org.springframework.data."))))
                        .and(not(hasSuperType(named("org.springframework.messaging.core.AbstractMessageSendingTemplate"))))
                        .and(not(nameEndsWith("Configuration")))
                        .and(not(nameEndsWith("RefreshScope")))
                        .and(not(nameEndsWith("ConfigurationProperties")))
                        .and(not(isSynthetic()))
                        .and(not(nameMatches(".*\\$\\$.*")))     // можно убрать, если хотите ловить CGLIB
                        .and(not(nameContains("CGLIB")))         // —//—
                        .and(not(nameContains("ByteBuddy")))
                        .and(not(nameContains("$")))
                        .and(not(getExcludedSuperTypeMatcher()));

        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(monitoredTypes)
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.method(
                                        any()
                                                .and(not(isAbstract()))
                                                .and(not(isAnnotatedWith(nameEndsWith("Bean"))))
                                                .and(not(isAnnotatedWith(nameEndsWith("ExceptionHandler"))))
                                                .and(not(isAnnotatedWith(named("org.springframework.scheduling.annotation.Scheduled"))))
                                                .and(not(isAnnotatedWith(named("org.springframework.kafka.annotation.KafkaListener"))))
                                                .and(not(isAnnotatedWith(named("org.springframework.messaging.handler.annotation.MessageMapping"))))
                                                .and(not(isAnnotatedWith(named("org.springframework.messaging.handler.annotation.SendTo"))))
                                                .and(not(nameMatches(".*\\$.*")))
                                                .and(not(isSynthetic()))
                                                .and(not(isDeclaredBy(Object.class)))
                                                .and(not(isDeclaredBy(Enum.class)))
                                                .and(not(isAnnotatedWith(nameEndsWith("PostConstruct"))))

                                )
                                // Вместо Advice.to(...) используем MethodDelegation.to(...)
                                .intercept(MethodDelegation.to(BasicInterceptor.class))
                )
                .installOn(instrumentation);
    }


    public static class BasicInterceptor {

        @RuntimeType
        public static Object intercept(
                @Origin Method method,
                @AllArguments Object[] args,
                @SuperCall Callable<?> callable
        ) throws Throwable {
            if (LoggerStatusContent.getEnabledProfile()) return callable.call();

            String UUIDMessage = "";
            Object result = null;
            try {
                if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringStaticMethod() &&
                        isStaticMethod(method)) {
                    return callable.call();
                }


                if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringOnlySpringComponent()) {
                    if (isSerializationContext()) {
                        return callable.call();
                    }
                }


                Pair<MethodTypeEnum, Boolean> flagNewSpan = identificationMethod(method);


                if (!flagNewSpan.getVal() && ContextManager.isMessageIdQueueEmpty()) {
                    return callable.call();
                }


                String urlVal = "";
                String serviceCallId = "";

                if (flagNewSpan.getVal() &&
                        (flagNewSpan.getKey() != MethodTypeEnum.METHOD &&
                                flagNewSpan.getKey() != MethodTypeEnum.SCHEDULER) &&
                        ContextManager.isMessageIdQueueEmpty()
                ) {
                    UUIDMessage = ContextManager.getMessageStart();
                    urlVal = ContextManager.getUrlStart();
                    serviceCallId = ContextManager.getServiceCallId();
                } else {

                    UUIDMessage = UuidCreator.getTimeBased().toString();
                }

                if (flagNewSpan.getVal()) {
                    ContextManager.setMethodInpointName(method.getName());
                    ContextManager.setClassInpointName(method.getDeclaringClass().getName());
                    ContextManager.setMessageInpointId(UUIDMessage);
                }

                // Build args and optionally REST extras
                String startArgs = ReflectionUtils.objectToString(paramConvert(args, method));
                boolean isRestMethod = MethodTypeEnum.getListWebMethodType().contains(flagNewSpan.getKey());
                if (flagNewSpan.getVal() && isRestMethod) {

                    java.util.Map<String, java.util.List<String>> headers = ContextManager.getRequestHeaders();

                    // Try to get body from ContextManager, if not available get from RequestBodyCollector
                    byte[] bodyBytes = ContextManager.getRequestBodyBytes();
                    if (bodyBytes == null || bodyBytes.length == 0) {
                        // Body not yet saved to ContextManager, get it from collector and save
                        bodyBytes = RequestBodyCollector.getBytes();
                        if (bodyBytes != null && bodyBytes.length > 0) {
                            ContextManager.setRequestBodyBytes(bodyBytes);
                        }
                    }

                    String headersStr = headers != null ? ReflectionUtils.objectToString(headers) : null;
                    String bodyStr = null;
                    try {
                        if (bodyBytes != null && bodyBytes.length > 0) {
                            // Check if this is a file upload based on Content-Type
                            boolean isFile = false;
                            if (headers != null) {
                                java.util.List<String> contentTypes = headers.get("content-type");
                                if (contentTypes == null) contentTypes = headers.get("Content-Type");
                                if (contentTypes != null && !contentTypes.isEmpty()) {
                                    String contentType = contentTypes.get(0).toLowerCase();
                                    isFile = contentType.contains("multipart/form-data")
                                            || contentType.contains("application/octet-stream")
                                            || contentType.contains("image/")
                                            || contentType.contains("video/")
                                            || contentType.contains("audio/")
                                            || contentType.contains("application/pdf")
                                            || contentType.contains("application/zip")
                                            || contentType.contains("application/x-")
                                            || contentType.contains("font/");
                                }
                            }

                            // Also check by size - if > 1MB, likely a file
                            if (bodyBytes.length > 1024 * 1024) {
                                isFile = true;
                            }

                            if (isFile) {
                                bodyStr = "[file send]";

                            } else {
                                Charset bodyCharset = StandardCharsets.UTF_8;
                                bodyStr = new String(bodyBytes, bodyCharset);

                            }
                        }
                    } catch (Exception e) {
                        if (io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent.isErrorsOrDebug()) {
                            System.err.println("ByteBuddyAgentBasic: error converting body: " + e.getMessage());
                        }
                    }

                    sendMessageStart(
                            UUIDMessage,
                            method.getDeclaringClass().getName(),
                            method.getName(),
                            ContextManager.getTraceId(),
                            ContextManager.getSpanId(),
                            OffsetDateTime.now(),
                            ContextManager.getParentIdMessageIdQueue(),
                            flagNewSpan.getVal(),
                            startArgs,
                            flagNewSpan.getKey().toString(),
                            urlVal,
                            serviceCallId,
                            ContextManager.getMethodInpointName(),
                            ContextManager.getMessageInpointId(),
                            ContextManager.getClassInpointName(),
                            headersStr,
                            bodyStr
                    );

                    ContextManager.setRequestHeaders(null);
                    ContextManager.setRequestBodyBytes(null);
                    RequestBodyCollector.reset();

                } else {
                    sendMessageStart(
                            UUIDMessage,
                            method.getDeclaringClass().getName(),
                            method.getName(),
                            ContextManager.getTraceId(),
                            ContextManager.getSpanId(),
                            OffsetDateTime.now(),
                            ContextManager.getParentIdMessageIdQueue(),
                            flagNewSpan.getVal(),
                            startArgs,
                            flagNewSpan.getKey().toString(),
                            urlVal,
                            serviceCallId,
                            ContextManager.getMethodInpointName(),
                            ContextManager.getMessageInpointId(),
                            ContextManager.getClassInpointName()
                    );
                }

                ContextManager.setMethodCallContextQueue(UUIDMessage);

            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("onMethodEnter error: " + method + " " + e.getMessage());
                }
            }

            Throwable thrown = null;
            try {
                result = callable.call();
            } catch (Throwable t) {
                thrown = t;
                throw t;
            } finally {


                try {
                    Object retVal = result;
                    if (result instanceof CompletableFuture) {
                        retVal = ((CompletableFuture<?>) result).thenAccept(UtilsDataConvert::handleResult);
                    }

                    String errorCallMessage = "";
                    if (thrown != null) {
                        errorCallMessage = getaNullThrowable(thrown);
                    }

                    sendMessageEnd(
                            UUIDMessage,
                            OffsetDateTime.now(),
                            errorCallMessage,
                            ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                            ContextManager.getTraceId(),
                            ContextManager.getSpanId()
                    );

                    ContextManager.removeLastQueue();

                } catch (Exception e) {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.err.println("onMethodExit error: " + method + " " + e.getMessage());
                    }
                }

            }
            return result;
        }
    }
}
