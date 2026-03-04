package io.bitdive.aspect;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils;
import io.bitdive.parent.utils.MethodTypeEnum;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.bitdive.parent.message_producer.MessageService.sendMessageEnd;
import static io.bitdive.parent.message_producer.MessageService.sendMessageStart;
import static io.bitdive.parent.trasirovka.agent.utils.DataUtils.*;

@Component
@Aspect
public class RepositoryAspect {

    private static final ConcurrentMap<Class<?>, Class<?>> REPO_INTERFACE_CACHE = new ConcurrentHashMap<>();


    @Around(" (execution(* org.springframework.data.repository.Repository+.*(..)) || execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..)) ) && !execution(* java.lang.Object.*(..))")
    public Object aroundRepositoryMethods(ProceedingJoinPoint joinPoint) throws Throwable {
        if (LoggerStatusContent.getEnabledProfile()) return joinPoint.proceed();
        String UUIDMessage = UuidCreator.getTimeBased().toString();
        Object retVal = null;
        Throwable thrown = null;

        MethodSignature methodSig = (MethodSignature) joinPoint.getSignature();
        Class<?> repoInterface = resolveRepositoryInterface(joinPoint.getThis(), methodSig);
        String className = repoInterface.getName();
        try {
            sendMessageStart(
                    UUIDMessage,
                    className,
                    methodSig.getName(),
                    "",
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    OffsetDateTime.now(),
                    ContextManager.getParentIdMessageIdQueue(),
                    false,
                    ReflectionUtils.objectToString(paramConvert(joinPoint.getArgs(), methodSig.getMethod())),
                    MethodTypeEnum.DB.toString(), "", "",
                    ContextManager.getMethodInpointName(),
                    ContextManager.getMessageInpointId(),
                    ContextManager.getClassInpointName()
            );

            ContextManager.setMethodCallContextQueue(UUIDMessage);

            retVal = joinPoint.proceed();

        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {

            sendMessageEnd(
                    UUIDMessage,
                    OffsetDateTime.now(),
                    ReflectionUtils.objectToString(thrown),
                    ReflectionUtils.objectToString(methodReturnConvert(retVal)),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId()
            );

            ContextManager.removeLastQueue();
        }

        return retVal;
    }

    private Class<?> resolveRepositoryInterface(Object proxy, MethodSignature sig) {
        Class<?> proxyClass = proxy.getClass();

        return REPO_INTERFACE_CACHE.computeIfAbsent(proxyClass, pc -> {
            Class<?>[] interfaces = AopProxyUtils.proxiedUserInterfaces(proxy);
            return Arrays.stream(interfaces)
                    .filter(Class::isInterface)
                    .filter(iface ->
                            !iface.getName().startsWith("org.springframework.") &&
                                    !iface.getName().startsWith("jakarta.") )
                    .findFirst()
                    .orElse(sig.getDeclaringType());
        });
    }

}