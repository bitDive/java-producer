package io.bitdive.trasirovka.java_agent.method_advice;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.asm.Advice;
import org.springframework.http.HttpHeaders;

import java.lang.reflect.Field;

public class ResponseWebInterceptor {

    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.This Object thisObject) throws NoSuchFieldException, IllegalAccessException {
        try {
            Field headersField = getFieldFromHierarchy(thisObject.getClass(), "headers");
            if (headersField != null) {
                headersField.setAccessible(true);
                HttpHeaders headers = (HttpHeaders) headersField.get(thisObject);
                headers.add("x-BitDiv-custom-span-id", ContextManager.getSpanId());
                headers.add("x-BitDiv-custom-parent-message-id", ContextManager.getMessageIdQueue());
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("onMethodEnter " + " " + e.getMessage());
            }
        }
    }

    public static Field getFieldFromHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
