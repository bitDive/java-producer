package io.bitdive.parent.trasirovka.agent.byte_buddy_agent;


import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.utils.MethodTypeEnum;
import io.bitdive.parent.utils.Pair;
import io.bitdive.parent.utils.UtilsDataConvert;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
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
            return ElementMatchers.isAnnotatedWith(nameContains("org.springframework"));
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

    public static void init() {
        new AgentBuilder.Default()
                .type(
                        getApplicationPackedScanner(YamlParserConfig.getProfilingConfig().getApplication().getPackedScanner())
                                .and(getSpringComponentMatcher())
                                .and(ElementMatchers.not(ElementMatchers.isEnum()))
                                .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.named("io.bitdive.parent.anotations.NotMonitoring"))))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("Configuration")))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("RefreshScope")))
                                .and(ElementMatchers.not(ElementMatchers.nameEndsWith("ConfigurationProperties")))
                                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$\\$.*")))
                                .and(ElementMatchers.not(nameContains("CGLIB")))
                                .and(ElementMatchers.not(nameContains("ByteBuddy")))
                                .and(ElementMatchers.not(nameContains("$")))
                                .and(ElementMatchers.not(getExcludedSuperTypeMatcher()))
                )
                .transform((builder, typeDescription, classLoader, module, sd) ->
                        builder.method(ElementMatchers.any()
                                        .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("Bean"))))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("ExceptionHandler"))))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.named("org.springframework.scheduling.annotation.Scheduled"))))
                                        .and(ElementMatchers.not(ElementMatchers.nameMatches(".*\\$.*")))
                                        .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Enum.class)))
                                        .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.nameEndsWith("PostConstruct"))))

                                )
                                .intercept(Advice.to(BasicInterceptor.class))
                )
                .installOnByteBuddyAgent();
    }


    public static class BasicInterceptor {

        @Advice.OnMethodEnter
        public static void onMethodEnter(@Advice.Origin Method method, @Advice.AllArguments Object[] args) {
            try {
                if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringStaticMethod() && isStaticMethod(method)) {
                    return;
                }
                if (isSerializationContext()) return;

                Pair<MethodTypeEnum, Boolean> flagNewSpan = identificationMethod(method);

                if (!flagNewSpan.getVal() && ContextManager.isMessageIdQueueEmpty()) {
                    return;
                }

                String UUIDMessage = null;

                String urlVal = "";
                if (flagNewSpan.getVal() &&
                        (flagNewSpan.getKey() != MethodTypeEnum.METHOD && flagNewSpan.getKey() != MethodTypeEnum.SCHEDULER)
                        && ContextManager.isMessageIdQueueEmpty()
                ) {
                    UUIDMessage = ContextManager.getMessageStart();
                    urlVal = ContextManager.getUrlStart();
                } else {
                    UUIDMessage = UuidCreator.getTimeBased().toString();
                }

                sendMessageStart(
                        YamlParserConfig.getProfilingConfig().getApplication().getModuleName(),
                        YamlParserConfig.getProfilingConfig().getApplication().getServiceName(),
                        UUIDMessage,
                        method.getDeclaringClass().getName(),
                        method.getName(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        OffsetDateTime.now(),
                        ContextManager.getParentIdMessageIdQueue(),
                        flagNewSpan.getVal(),
                        ReflectionUtils.objectToString(paramConvert(args)),
                        flagNewSpan.getKey().toString(),
                        urlVal
                );

                ContextManager.setMethodCallContextQueue(UUIDMessage);
            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("onMethodEnter " + method + " " + e.getMessage());
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(@Advice.Origin Method method,
                                  @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                                  @Advice.Thrown Throwable thrown) {
            try {
                if (ContextManager.isMessageIdQueueEmpty()) {
                    return;
                }
                if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringStaticMethod() && isStaticMethod(method)) {
                    return;
                }
                if (isSerializationContext()) return;

                Object retVal = returned;
                if (returned instanceof CompletableFuture) {
                    retVal = ((CompletableFuture<?>) returned).thenAccept(UtilsDataConvert::handleResult);
                }

                String errorCallMessage = "";
                if (thrown != null) {
                    errorCallMessage = getaNullThrowable(thrown);
                }

                sendMessageEnd(
                        ContextManager.getMessageIdQueueNew(),
                        OffsetDateTime.now(),
                        errorCallMessage,
                        ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId()
                );
                ContextManager.removeLastQueue();


            } catch (Exception e) {
                if (LoggerStatusContent.isErrorsOrDebug()) {
                    System.err.println("onMethodEnter " + method + " " + e.getMessage());
                }
            }
        }
    }
}
