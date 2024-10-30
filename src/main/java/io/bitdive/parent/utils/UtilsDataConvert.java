package io.bitdive.parent.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UtilsDataConvert {
    private final static Map<String, Pair<MethodTypeEnum, Boolean>> mapNewSpan = new ConcurrentHashMap<>();
    private final static Map<String, Boolean> mapStaticMethod = new ConcurrentHashMap<>();
    private final static List<String> listNewFlagComponent = Arrays.asList("org.springframework.web.bind.annotation", "Scheduled");

    public static Object handleResult(Object result) {
        return result;
    }

    public static Boolean isStaticMethod(Method method) {
        String findClassAndMethod = String.format("%s//%s", method.getDeclaringClass().getName(), method.getName());
        return mapStaticMethod.computeIfAbsent(findClassAndMethod, key -> Modifier.isStatic(method.getModifiers()));
    }

    public static boolean isSerializationContext() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("io.bitdive.parent.trasirovka.agent.utils.ReflectionUtils"))
                return true;
        }
        return false;
    }

    public static Pair<MethodTypeEnum, Boolean> identificationMethod(Method method) {
        String findClassAndMethod = String.format("%s//%s", method.getDeclaringClass().getName(), method.getName());

        return mapNewSpan.computeIfAbsent(findClassAndMethod, key -> {
            Annotation[] annotations = method.getDeclaredAnnotations();

            boolean isNewSpan = false;
            MethodTypeEnum methodType = MethodTypeEnum.METHOD;
            for (Annotation annotation : annotations) {

                if (listNewFlagComponent.stream().anyMatch(s -> annotation.annotationType().getPackage().getName().contains(s))) {
                    isNewSpan = true;
                }
                for (MethodTypeEnum methodTypeEnum : MethodTypeEnum.getListMethodFlagInPoint()) {
                    if (annotation.annotationType().getName().contains(methodTypeEnum.getAnnotationName())) {
                        methodType = methodTypeEnum;
                        break;
                    }
                }
            }

            return new Pair<>(methodType, isNewSpan);
        });
    }
}
