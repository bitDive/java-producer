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

import static io.bitdive.parent.message_producer.MessageService.sendMessageStompSend;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.methodReturnConvert;

@Aspect
@Component
public class StompSendToAspect {

    @Autowired
    private Environment environment;

    private final ConcurrentMap<Method, String> sendToCache = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.messaging.handler.annotation.SendTo)")
    public Object aroundSendTo(ProceedingJoinPoint joinPoint) throws Throwable {
        if (LoggerStatusContent.getEnabledProfile()) return joinPoint.proceed();
        String uuidMessage = UuidCreator.getTimeBased().toString();
        Throwable thrown = null;
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();
        OffsetDateTime startTime = OffsetDateTime.now();

        Method method = methodSig.getMethod();
        String destination = sendToCache.get(method);
        if (destination == null) {
            destination = resolveSendToDestination(method);
            sendToCache.put(method, destination);
        }

        Object retVal = null;
        try {
            retVal = joinPoint.proceed();
            return retVal;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            sendMessageStompSend(
                    uuidMessage,
                    ContextManager.getSpanId(),
                    ContextManager.getTraceId(),
                    methodSig.getDeclaringTypeName(),
                    methodSig.getName(),
                    destination,
                    ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                    startTime,
                    OffsetDateTime.now(),
                    ContextManager.getMessageIdQueueNew(),
                    getaNullThrowable(thrown),
                    ContextManager.getMethodInpointName(),
                    ContextManager.getMessageInpointId(),
                    ContextManager.getClassInpointName()
            );
        }
    }

    private String resolveSendToDestination(Method method) {
        try {
            Annotation[] annotations = method.getDeclaredAnnotations();
            for (Annotation annotation : annotations) {
                String name = annotation.annotationType().getName();
                if ("org.springframework.messaging.handler.annotation.SendTo".equals(name)) {
                    try {
                        Method value = annotation.annotationType().getMethod("value");
                        String[] vals = (String[]) value.invoke(annotation);
                        if (vals != null && vals.length > 0) {
                            return environment.resolvePlaceholders(vals[0]);
                        }
                    } catch (NoSuchMethodException ignore) {
                    }
                    try {
                        Method destinations = annotation.annotationType().getMethod("destinations");
                        String[] vals = (String[]) destinations.invoke(annotation);
                        if (vals != null && vals.length > 0) {
                            return environment.resolvePlaceholders(vals[0]);
                        }
                    } catch (NoSuchMethodException ignore) {
                    }
                }
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.err.println("resolveSendToDestination ERROR: " + e.getMessage());
            }
        }
        return "";
    }

}


