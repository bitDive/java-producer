package io.bitdive.parent.trasirovka.agent.method_advice;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.utils.MethodTypeEnum;
import io.bitdive.parent.utils.Pair;
import io.bitdive.parent.utils.UtilsDataConvert;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static io.bitdive.parent.message_producer.MessageService.sendMessageEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.methodReturnConvert;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;
import static io.bitdive.parent.utils.UtilsDataConvert.*;

public class BasicInterceptor {

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
            if (flagNewSpan.getVal() && MethodTypeEnum.getListWebMethodType().contains(flagNewSpan.getKey())) {
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
                    LocalDateTime.now(),
                    ContextManager.getParentIdMessageIdQueue(),
                    flagNewSpan.getVal(),
                    ReflectionUtils.objectToString(paramConvert(args)),
                    flagNewSpan.getKey().toString(),
                    urlVal
            );

            ContextManager.setMethodCallContextQueue(UUIDMessage);
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.out.println("onMethodEnter " + method + " " + e.getMessage());
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
                errorCallMessage = thrown.getMessage();
            }

            sendMessageEnd(
                    ContextManager.getMessageIdQueueNew(),
                    LocalDateTime.now(),
                    errorCallMessage,
                    ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                    ContextManager.getTraceId()
            );
            ContextManager.removeLastQueue();


        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("onMethodEnter " + method + " " + e.getMessage());
            }
        }
    }
}
