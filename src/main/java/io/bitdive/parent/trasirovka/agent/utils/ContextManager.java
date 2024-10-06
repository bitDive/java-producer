package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.dto.TraceMethodContext;
import lombok.var;

import java.util.Deque;
import java.util.Optional;

public class ContextManager {

    private static final ThreadLocal<TraceMethodContext> contextThreadLocal =ThreadLocal.withInitial(TraceMethodContext::new);

    public static void setSpanID(String spanId) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setSpanId(spanId));
    }

    public static void setContextThread(TraceMethodContext value) {
        var traceMethodContext= new TraceMethodContext();
        traceMethodContext.setTraceId(value.getTraceId());
        traceMethodContext.setSpanId(value.getSpanId());
        if (!value.getMethodCallContextQueue().isEmpty()) {
            traceMethodContext.getMethodCallContextQueue().offerLast(value.getMethodCallContextQueue().peekLast());
        }
        contextThreadLocal.set(traceMethodContext);
    }

    public static void setParentMessageIdOtherService(String parentId) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> {
                    if (traceMethodContext.getMethodCallContextQueue().isEmpty()) {
                        traceMethodContext.getMethodCallContextQueue().offerLast(parentId);
                    }
                }
        );
    }


    public static TraceMethodContext getContext() {
        return contextThreadLocal.get();
    }

    public static String getTraceId() {
       return getContestThreadLocalOptional().map(TraceMethodContext::getTraceId)
               .orElseThrow(() -> new RuntimeException("TraceId not found"));
    }

    public static String getSpanId() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getSpanId).orElse(null);
    }

    public static String getMessageIdQueue() {
        return getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(Deque::peekLast)
                .orElse(null);
    }



    public static String getKafkaMessageQueue() {
        return getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(Deque::peekLast)
                .orElseThrow(() -> new RuntimeException("KafkaMessageId not found"));
    }

    public static void setMethodCallContextQueue(String methodCallId) {
        getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(queue -> queue.offerLast(methodCallId));
    }

    public static void removeLastQueue() {
        getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .ifPresent(Deque::pollLast);

    }

    private static Optional<TraceMethodContext> getContestThreadLocalOptional() {
        return Optional.ofNullable(contextThreadLocal.get());
    }



}
