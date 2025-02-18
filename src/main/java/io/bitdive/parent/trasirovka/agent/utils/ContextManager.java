package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.dto.TraceMethodContext;

import java.util.Deque;
import java.util.Optional;

public class ContextManager {

    private static final ThreadLocal<TraceMethodContext> contextThreadLocal =ThreadLocal.withInitial(TraceMethodContext::new);

    public static void createNewRequest() {
        contextThreadLocal.set(new TraceMethodContext());
    }

    public static void setServiceCallId(String serviceCallId) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setServiceCallId(serviceCallId));
    }

    public static void setSpanID(String spanId) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setSpanId(spanId));
    }

    public static void setParentMessageIdOtherService(String parentId) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setParentIdForRest(parentId));
    }

    public static void setContextThread(TraceMethodContext value) {
        TraceMethodContext traceMethodContext = new TraceMethodContext();
        traceMethodContext.setTraceId(value.getTraceId());
        traceMethodContext.setSpanId(value.getSpanId());

        traceMethodContext.setServiceCallId(value.getServiceCallId());
        traceMethodContext.setParentIdForRest(value.getParentIdForRest());

        traceMethodContext.setUrlStart(value.getUrlStart());
        traceMethodContext.setStartMessageId(value.getStartMessageId());
        if (!value.getMethodCallContextQueue().isEmpty()) {
            traceMethodContext.getMethodCallContextQueue().offerLast(value.getMethodCallContextQueue().peekLast());
        }
        contextThreadLocal.set(traceMethodContext);
    }

    public static String getMessageStart() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getStartMessageId).orElse("");
    }

    public static void setUrlStart(String urlStart) {
        getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setUrlStart(urlStart));
    }

    public static String getUrlStart() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getUrlStart).orElse("null");
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

    public static String getServiceCallId() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getServiceCallId).orElse(null);
    }

    private static String getMessageIdQueue() {
        return getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(Deque::peekLast)
                .orElse("");
    }

    public static String getMessageIdQueueNew() {
        return getContestThreadLocalOptional()
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(Deque::peekLast)
                .orElse("");
    }

    public static String getParentIdMessageIdQueue() {
        return getContestThreadLocalOptional()
                .filter(traceMethodContext -> !traceMethodContext.getMethodCallContextQueue().isEmpty())
                .map(TraceMethodContext::getMethodCallContextQueue)
                .map(Deque::peekLast)
                .orElse(getContestThreadLocalOptional().map(TraceMethodContext::getParentIdForRest).orElse(""));
    }

    public static boolean isMessageIdQueueEmpty() {
        return getMessageIdQueue().isEmpty();
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
