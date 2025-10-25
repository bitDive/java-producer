package io.bitdive.parent.dto;

import com.github.f4b6a3.uuid.UuidCreator;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

@Getter
@Setter
public class TraceMethodContext {
    public TraceMethodContext() {
        traceId = UuidCreator.getTimeBased().toString();
        spanId = UuidCreator.getTimeBased().toString();
        startMessageId = UuidCreator.getTimeBased().toString();

        methodCallContextQueue = new LinkedBlockingDeque<>();
        parentIdForRest = null;

    }
    private BlockingDeque<String> methodCallContextQueue;
    private String traceId;
    private String spanId;

    private String serviceCallId;

    private String parentIdForRest;

    private String startMessageId;
    private String urlStart;


    private String classInpointName;
    private String methodInpointName;
    private String messageInpointId;

    // Incoming HTTP request capture (server side)
    private Map<String, List<String>> requestHeaders;
    private byte[] requestBodyBytes;
}
