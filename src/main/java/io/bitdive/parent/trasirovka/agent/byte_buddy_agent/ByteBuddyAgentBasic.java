package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.anotations.MonitoringClass;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
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

    public static ResettableClassFileTransformer init(Instrumentation instrumentation) {
        ElementMatcher.Junction<TypeDescription> monitoredTypes =
                getApplicationPackedScanner(
                        YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner())
                        .and(getSpringComponentMatcher())
                        .and(not(isEnum()))
                        .and(not(isAnnotatedWith(named("io.bitdive.parent.anotations.NotMonitoring"))))
                        .and(not(isAnnotatedWith(nameContains("org.springframework.data."))))
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


                sendMessageStart(
                        UUIDMessage,
                        method.getDeclaringClass().getName(),
                        method.getName(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        OffsetDateTime.now(),
                        ContextManager.getParentIdMessageIdQueue(),
                        flagNewSpan.getVal(),
                        ReflectionUtils.objectToString(paramConvert(args, method)),
                        flagNewSpan.getKey().toString(),
                        urlVal,
                        serviceCallId
                );

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
