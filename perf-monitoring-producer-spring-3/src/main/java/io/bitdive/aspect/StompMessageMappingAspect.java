package io.bitdive.aspect;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.bitdive.parent.message_producer.MessageService.sendMessageStompConsumer;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;

@Aspect
@Component
public class StompMessageMappingAspect {

    @Autowired
    private Environment environment;

    private final ConcurrentMap<Method, CachedMappingInfo> mappingCache = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.messaging.handler.annotation.MessageMapping)")
    public Object aroundMessageMapping(ProceedingJoinPoint joinPoint) throws Throwable {
        if (LoggerStatusContent.getEnabledProfile()) return joinPoint.proceed();
        ContextManager.createNewRequest();
        String uuidMessage = UuidCreator.getTimeBased().toString();
        Throwable thrown = null;
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();
        OffsetDateTime startTime = OffsetDateTime.now();

        Method method = methodSig.getMethod();
        CachedMappingInfo cachedInfo = mappingCache.get(method);
        if (cachedInfo == null) {
            cachedInfo = resolveMessageMapping(method);
            mappingCache.put(method, cachedInfo);
        }

        ContextManager.setMethodCallContextQueue(uuidMessage);
        Object retVal;
        try {
            retVal = joinPoint.proceed();
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            ContextManager.setMethodInpointName(methodSig.getName());
            ContextManager.setClassInpointName(methodSig.getDeclaringTypeName());
            ContextManager.setMessageInpointId(uuidMessage);
            String destination = cachedInfo.getDestination();
            sendMessageStompConsumer(
                    uuidMessage,
                    methodSig.getDeclaringTypeName(),
                    methodSig.getName(),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    startTime,
                    OffsetDateTime.now(),
                    destination,
                    ReflectionUtils.objectToString(paramConvert(joinPoint.getArgs(), methodSig.getMethod())),
                    getaNullThrowable(thrown),
                    true,
                    ContextManager.getMethodInpointName(),
                    ContextManager.getMessageInpointId(),
                    ContextManager.getClassInpointName()
            );
        }
        return retVal;
    }

    private CachedMappingInfo resolveMessageMapping(Method method) {
        Annotation[] annotations = method.getDeclaredAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName().equals("org.springframework.messaging.handler.annotation.MessageMapping")) {
                try {
                    Method valueMethod = annotation.annotationType().getMethod("value");
                    String[] values = (String[]) valueMethod.invoke(annotation);
                    for (int i = 0; i < values.length; i++) values[i] = environment.resolvePlaceholders(values[i]);
                    String destination = String.join(",", values);
                    return new CachedMappingInfo(destination);
                } catch (Exception ignore) {
                    return new CachedMappingInfo("");
                }
            }
        }
        return new CachedMappingInfo("");
    }

    private static class CachedMappingInfo {
        private final String destination;

        private CachedMappingInfo(String destination) {
            this.destination = destination;
        }

        public String getDestination() {
            return destination;
        }
    }
}


