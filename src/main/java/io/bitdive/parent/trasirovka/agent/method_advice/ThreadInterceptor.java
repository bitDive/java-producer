package io.bitdive.parent.trasirovka.agent.method_advice;

import io.bitdive.parent.dto.TraceMethodContext;
import io.bitdive.parent.trasirovka.agent.utils.ContextCallableCustom;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.Callable;

public class ThreadInterceptor {
    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Origin String method,
            @Advice.Argument(value = 0, readOnly = false) Callable<?> callable) {

        TraceMethodContext currentContext = ContextManager.getContext();
        callable = new ContextCallableCustom<>(callable, currentContext);
    }
}
