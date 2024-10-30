package io.bitdive.parent.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

@Getter
@Setter
public class TraceMethodContext {
    public TraceMethodContext() {
        traceId = UUID.randomUUID().toString();
        spanId = UUID.randomUUID().toString();
        methodCallContextQueue = new LinkedBlockingDeque<>();
        parentIdForRest = null;

    }
    private BlockingDeque<String> methodCallContextQueue;
    private String traceId;
    private String spanId;

    private String parentIdForRest;

    private String startMessageId;
    private String urlStart;
}
