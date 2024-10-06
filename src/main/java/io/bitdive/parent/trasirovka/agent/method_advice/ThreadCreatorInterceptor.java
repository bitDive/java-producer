package io.bitdive.parent.trasirovka.agent.method_advice;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.ContextRunnableCustom;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

public class ThreadCreatorInterceptor {
    @Advice.OnMethodEnter
    public static void onMethodEnter(@Advice.Origin Method method ,
                                     @Advice.Argument(value = 0, readOnly = false) Runnable runnable)  {
        runnable= new ContextRunnableCustom(runnable, ContextManager.getContext());
    }
}
