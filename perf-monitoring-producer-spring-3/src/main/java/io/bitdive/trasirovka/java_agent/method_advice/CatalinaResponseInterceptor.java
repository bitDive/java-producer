package io.bitdive.trasirovka.java_agent.method_advice;

import io.bitdive.parent.message_producer.MessageService;
import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import net.bytebuddy.asm.Advice;

public class CatalinaResponseInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object responseObj) {
        if (responseObj instanceof org.apache.catalina.connector.Response response) {
            MessageService.sendMessageWebResponse(
                    ContextManager.getMessageStart(),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    response.getStatus());
        }
    }
}
