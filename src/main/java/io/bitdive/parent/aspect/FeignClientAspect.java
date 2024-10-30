package io.bitdive.parent.aspect;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.utils.MethodTypeEnum;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

import static io.bitdive.parent.message_producer.MessageService.sendMessageEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.methodReturnConvert;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;

@Aspect
@Component
public class FeignClientAspect {

    @Around("@within(org.springframework.cloud.openfeign.FeignClient)")
    public Object aroundFeignClientMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        String UUIDMessage = UuidCreator.getTimeBased().toString();
        Object retVal = null;
        Throwable thrown = null;
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();

        try {

            sendMessageStart(
                    YamlParserConfig.getProfilingConfig().getApplication().getModuleName(),
                    YamlParserConfig.getProfilingConfig().getApplication().getServiceName(),
                    UUIDMessage,
                    methodSig.getDeclaringTypeName(),
                    methodSig.getName(),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    LocalDateTime.now(),
                    ContextManager.getParentIdMessageIdQueue(),
                    false,
                    ReflectionUtils.objectToString(paramConvert(joinPoint.getArgs())),
                    MethodTypeEnum.METHOD.toString(),
                    ""
            );


            ContextManager.setMethodCallContextQueue(UUIDMessage);

            retVal = joinPoint.proceed();

        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {

            sendMessageEnd(
                    UUIDMessage,
                    LocalDateTime.now(),
                    Optional.ofNullable(thrown).map(Throwable::getMessage).orElse(""),
                    ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                    ContextManager.getTraceId()
            );


            ContextManager.removeLastQueue();
        }

        return retVal;
    }
}
