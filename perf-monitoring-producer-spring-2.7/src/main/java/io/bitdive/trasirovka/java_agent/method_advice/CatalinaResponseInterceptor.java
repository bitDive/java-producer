package io.bitdive.trasirovka.java_agent.method_advice;

import io.bitdive.parent.trasirovka.agent.utils.ContextManager;
import net.bytebuddy.asm.Advice;

import static io.bitdive.parent.message_producer.MessageService.sendMessageWebResponse;

public class CatalinaResponseInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object responseObj) {
        if (responseObj instanceof org.apache.catalina.connector.Response) {
            org.apache.catalina.connector.Response response = (org.apache.catalina.connector.Response) responseObj;
            sendMessageWebResponse(
                    ContextManager.getMessageStart(),
                    ContextManager.getTraceId(),
                    ContextManager.getSpanId(),
                    response.getStatus()
            );
        }
    }

}
