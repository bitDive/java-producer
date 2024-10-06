package io.bitdive.aspect;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;

import java.util.Optional;

import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.*;
import static io.bitdive.service.MessageService.sendMessage;

@Aspect
public class RepositoryInterceptor {

   @Pointcut("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object aroundRepositoryMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        String UUIDMessage = UuidCreator.getTimeBased().toString();
        Object retVal = null;
        Throwable thrown = null;
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();

        try {

            String strMessage =
                            YamlParserConfig.getProfilingConfig().getApplication().getModuleName() + "__" +
                            YamlParserConfig.getProfilingConfig().getApplication().getServiceName() + "__" +
                            UUIDMessage + "__" +
                            methodSig.getDeclaringTypeName() + "__" +
                            methodSig.getName() + "__" +
                            ContextManager.getTraceId() + "__" +
                            ContextManager.getSpanId() + "__" +
                            getLocalDateTimeJackson() + "__" +
                            Optional.ofNullable(ContextManager.getMessageIdQueue()).orElse("") + "__" +
                            false + "__" +
                            ReflectionUtils.objectToString(paramConvert(joinPoint.getArgs()));

            sendMessage(strMessage);

            ContextManager.setMethodCallContextQueue(UUIDMessage);

            // Выполнение метода
            retVal = joinPoint.proceed();

        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {

            String strMessage =
                            UUIDMessage + "__" +
                            getLocalDateTimeJackson() + "__" +
                            Optional.ofNullable(thrown).map(Throwable::getMessage).orElse("") + "__" +
                            ReflectionUtils.objectToString(methodReturnConvert(retVal))+ "__" +
                            ContextManager.getTraceId();

            sendMessage(strMessage );

            ContextManager.removeLastQueue();
        }

        return retVal;
    }

}