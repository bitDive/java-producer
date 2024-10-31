package io.bitdive.parent.trasirovka.agent.method_advice;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import io.bitdive.parent.trasirovka.agent.utils.SQLUtils;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

import static io.bitdive.parent.message_producer.MessageService.sendMessageSQL;


public class SqlInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object stmt, @Advice.Origin Method method) {
        try {
            if (!ContextManager.isMessageIdQueueEmpty()) {
                sendMessageSQL(ContextManager.getMessageIdQueueNew(),
                        ContextManager.getTraceId(),
                        ContextManager.getSpanId(),
                        SQLUtils.getSQLFromStatement(stmt)
                );
            }
        } catch (Exception e) {
            if (LoggerStatusContent.isErrorsOrDebug()) {
                System.out.println("SqlInterceptor " + method + " " + e.getMessage());
            }
        }
    }

}
