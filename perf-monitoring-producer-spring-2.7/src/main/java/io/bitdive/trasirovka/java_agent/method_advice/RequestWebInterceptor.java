package io.bitdive.trasirovka.java_agent.method_advice;

import com.github.f4b6a3.uuid.UuidCreator;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import net.bytebuddy.asm.Advice;
import org.springframework.util.ObjectUtils;

public class RequestWebInterceptor {
    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.Origin String method , @Advice.Argument(value = 0) javax.servlet.http.HttpServletRequest httpServletRequest) {
        try {
            ContextManager.setMessageStart(UuidCreator.getTimeBased().toString());
            ContextManager.setUrlStart(httpServletRequest.getRequestURL().toString());
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
