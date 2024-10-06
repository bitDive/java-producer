package io.bitdive.java_agent.method_advice;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ObjectUtils;

public class RequestWebInterceptor {
    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.Origin String method , @Advice.Argument(value = 0) javax.servlet.http.HttpServletRequest httpServletRequest) {
        try {
            if (!ObjectUtils.isEmpty(httpServletRequest.getHeader("x-BitDiv-custom-span-id"))) {
                ContextManager.setSpanID(httpServletRequest.getHeader("x-BitDiv-custom-span-id"));
            }
            if (!ObjectUtils.isEmpty(httpServletRequest.getHeader("x-BitDiv-custom-parent-message-id"))) {
                ContextManager.setParentMessageIdOtherService(httpServletRequest.getHeader("x-BitDiv-custom-parent-message-id"));
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("onMethodEnter " + method + " " + e.getMessage());
            }
        }
    }
}
