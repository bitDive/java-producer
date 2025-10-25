package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.dto.TraceMethodContext;

import java.util.Deque;
import java.util.List;
import java.util.Map;
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

        traceMethodContext.setClassInpointName(value.getClassInpointName());
        traceMethodContext.setMethodInpointName(value.getMethodInpointName());
        traceMethodContext.setMessageInpointId(value.getMessageInpointId());


        traceMethodContext.setUrlStart(value.getUrlStart());
        traceMethodContext.setStartMessageId(value.getStartMessageId());

        // Copy incoming request captured fields
        traceMethodContext.setRequestHeaders(value.getRequestHeaders());
        traceMethodContext.setRequestBodyBytes(value.getRequestBodyBytes());
        if (!value.getMethodCallContextQueue().isEmpty()) {
            traceMethodContext.getMethodCallContextQueue().offerLast(value.getMethodCallContextQueue().peekLast());
        }
        contextThreadLocal.set(traceMethodContext);
    }


    public static String getClassInpointName() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getClassInpointName).orElse(null);
    }
    public static void setClassInpointName(String classInpointName) {
        if (getClassInpointName() == null)
            getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setClassInpointName(classInpointName));
    }

    public static String getMethodInpointName() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getMethodInpointName).orElse(null);
    }
    public static void setMethodInpointName(String methodInpointName) {
        if (getMethodInpointName() == null)
            getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setMethodInpointName(methodInpointName));
    }


    public static String getMessageInpointId() {
        return getContestThreadLocalOptional().map(TraceMethodContext::getMessageInpointId).orElse(null);
    }
    public static void setMessageInpointId(String messageInpointId) {
        if (getMessageInpointId() == null)
            getContestThreadLocalOptional().ifPresent(traceMethodContext -> traceMethodContext.setMessageInpointId(messageInpointId));
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

    /* ---------- Incoming request data ---------- */
    public static void setRequestHeaders(Map<String, List<String>> headers) {
        getContestThreadLocalOptional().ifPresent(ctx -> ctx.setRequestHeaders(headers));
    }

    public static Map<String, List<String>> getRequestHeaders() {
        return getContestThreadLocalOptional().map(ctx -> {
            Map<String, List<String>> v = ctx.getRequestHeaders();
            ctx.setRequestHeaders(null);
            return v;
        }).orElse(null);
    }

    public static void setRequestBodyBytes(byte[] body) {
        getContestThreadLocalOptional().ifPresent(ctx -> ctx.setRequestBodyBytes(body));
    }

    public static byte[] getRequestBodyBytes() {
        return getContestThreadLocalOptional().map(ctx -> {
            byte[] v = ctx.getRequestBodyBytes();
            ctx.setRequestBodyBytes(null);
            return v;
        }).orElse(null);
    }

}
