package io.bitdive.aspect;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.KafkaAgentStorage;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import lombok.Getter;
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

import static io.bitdive.parent.message_producer.MessageService.sendMessageKafkaConsumer;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.getaNullThrowable;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.paramConvert;

@Aspect
@Component
public class KafkaListenerAspect {

    @Autowired
    private Environment environment;

    private final ConcurrentMap<Method, CachedKafkaInfo> kafkaListenerCache = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object aroundKafkaListener(ProceedingJoinPoint joinPoint) throws Throwable {
        if (LoggerStatusContent.getEnabledProfile()) return joinPoint.proceed();
        ContextManager.createNewRequest();
        String uuidMessage = UuidCreator.getTimeBased().toString();
        Throwable thrown = null;
        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();
        OffsetDateTime startTime = OffsetDateTime.now();
        String topicName = "";
        String groupName = "";

        Method method = methodSig.getMethod();

        CachedKafkaInfo cachedInfo = kafkaListenerCache.get(method);
        if (cachedInfo != null) {
            topicName = cachedInfo.getTopicName();
            groupName = cachedInfo.getGroupName();
        } else {
            Annotation[] annotations = method.getDeclaredAnnotations();
            for (Annotation annotation : annotations) {
                if (annotation.annotationType().getName().equals("org.springframework.kafka.annotation.KafkaListener")) {
                    try {
                        Method topicsMethod = annotation.annotationType().getMethod("topics");
                        String[] topics = (String[]) topicsMethod.invoke(annotation);

                        Method groupIdMethod = annotation.annotationType().getMethod("groupId");
                        String groupId = (String) groupIdMethod.invoke(annotation);

                        for (int i = 0; i < topics.length; i++) {
                            topics[i] = environment.resolvePlaceholders(topics[i]);
                        }
                        groupId = environment.resolvePlaceholders(groupId);

                        topicName = String.join(",", topics);
                        groupName = groupId;

                        cachedInfo = new CachedKafkaInfo(topicName, groupName);
                        kafkaListenerCache.put(method, cachedInfo);
                    } catch (Exception e) {
                        if (LoggerStatusContent.isErrorsOrDebug())
                            System.err.println("aroundKafkaListener ERROR: " + e.getMessage());
                    }
                    break;
                }
            }
        }
        ContextManager.setMethodCallContextQueue(uuidMessage);
        Object retVal;
        try {
            retVal = joinPoint.proceed();
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            ContextManager.setClassInpointName(methodSig.getDeclaringTypeName());
            ContextManager.setMethodInpointName(methodSig.getName());
            ContextManager.setMessageInpointId(uuidMessage);

            sendMessageKafkaConsumer(
                    uuidMessage,
                    methodSig.getDeclaringTypeName(),
                    methodSig.getName(),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    startTime,
                    OffsetDateTime.now(),
                    true,
                    ReflectionUtils.objectToString(paramConvert(joinPoint.getArgs(), method)),
                    topicName,
                    groupName,
                    KafkaAgentStorage.getBootstrap(),
                    getaNullThrowable(thrown),
                    ContextManager.getMethodInpointName(),
                    ContextManager.getMessageInpointId(),
                    ContextManager.getClassInpointName()
            );
        }
        return retVal;
    }

    @Getter
    private static class CachedKafkaInfo {
        private final String topicName;
        private final String groupName;

        public CachedKafkaInfo(String topicName, String groupName) {
            this.topicName = topicName;
            this.groupName = groupName;
        }

    }

}