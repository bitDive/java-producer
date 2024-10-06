package io.bitdive.trasirovka.java_agent.method_advice;


import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.*;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getLocalDateTimeJackson;
import static io.bitdive.service.MessageService.sendMessage;

public class BasicInterceptor {

    private final static Map<String, Boolean> mapNewSpan = new ConcurrentHashMap<>();
    private final static Map<String, Boolean> mapStaticMethod = new ConcurrentHashMap<>();
    private final static List listNewFlagComponent=  Arrays.asList("org.springframework.web.bind.annotation", "org.springframework.scheduling.annotation");

    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.Origin Method method, @Advice.AllArguments Object[] args) {
        try {
            if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringStaticMethod() && isStaticMethod(method)) {
                return;
            }

            boolean flagNewSpan = isFlagNewSpan(method);
            String UUIDMessage = UuidCreator.getTimeBased().toString();;

            String strMessage =
                    "AFTER__" +
                    YamlParserConfig.getProfilingConfig().getApplication().getModuleName() + "__" +
                    YamlParserConfig.getProfilingConfig().getApplication().getServiceName() + "__" +
                    UUIDMessage + "__" +
                    method.getDeclaringClass().getName() + "__" +
                    method.getName() + "__" +
                    ContextManager.getTraceId() + "__" +
                    ContextManager.getSpanId() + "__" +
                    getLocalDateTimeJackson() + "__" +
                    Optional.ofNullable(ContextManager.getMessageIdQueue()).orElse("") + "__" +
                    flagNewSpan + "__" +
                    ReflectionUtils.objectToString(paramConvert(args));


            ContextManager.setMethodCallContextQueue(UUIDMessage);
             sendMessage(strMessage);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("onMethodEnter " + method + " " + e.getMessage());
            }
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable thrown) {
        try {
            if (!YamlParserConfig.getProfilingConfig().getMonitoring().getMonitoringStaticMethod() && isStaticMethod(method)) {
                return;
            }

            Object retVal = returned;
            if (returned instanceof CompletableFuture) {
                retVal = ((CompletableFuture<?>) returned).thenAccept(BasicInterceptor::handleResult);
            }

            String errorCallMessage = "";
            if (thrown != null) {
                errorCallMessage = thrown.getMessage();
            }

            String strMessage =
                    "BEFORE__" +
                    ContextManager.getMessageIdQueue() + "__" +
                    getLocalDateTimeJackson() + "__" +
                    errorCallMessage + "__" +
                    ReflectionUtils.objectToString(methodReturnConvert(retVal))+ "__" +
                    ContextManager.getTraceId();

            sendMessage( strMessage);
            ContextManager.removeLastQueue();


        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("onMethodEnter " + method + " " + e.getMessage());
            }
        }
    }


    public static Object handleResult(Object result) {
        return result;
    }

    public static Boolean isStaticMethod(Method method) {
        String findClassAndMethod = "%s//%s".formatted(method.getDeclaringClass().getName(), method.getName());
        return mapStaticMethod.computeIfAbsent(findClassAndMethod, key -> Modifier.isStatic(method.getModifiers()));
    }

    public static boolean isFlagNewSpan(Method method) {
        String findClassAndMethod = "%s//%s".formatted(method.getDeclaringClass().getName(), method.getName());

        return mapNewSpan.computeIfAbsent(findClassAndMethod, key -> {
            Annotation[] annotations = method.getDeclaredAnnotations();

            for (Annotation annotation : annotations) {
                if (listNewFlagComponent.contains(annotation.annotationType().getPackage().getName())) {
                    return true;
                }
            }
            return false;
        });
    }


}
