package io.bitdive.parent.trasirovka.agent.utils;

/**
 * ThreadLocal storage for outgoing RestTemplate request body (original object before serialization).
 *
 * <p>Why: in Spring Boot 2 / Spring 5, {@code ClientHttpRequest#getBody()} often exposes only a
 * {@code ByteArrayOutputStream} buffer (already serialized JSON), so the original DTO class is lost.
 * We capture the original object earlier (RestTemplate request callback) and use it for logging/tracing.
 *
 * <p>IMPORTANT: always clear to avoid ThreadLocal leaks.
 */
public final class OutgoingRestTemplateBodyContext {

    private static final ThreadLocal<Object> OUTGOING_BODY = new ThreadLocal<>();

    private OutgoingRestTemplateBodyContext() {
    }

    public static void set(Object body) {
        if (body == null) return;
        OUTGOING_BODY.set(body);
    }

    /**
     * Returns stored body and clears ThreadLocal.
     */
    public static Object getAndClear() {
        Object v = OUTGOING_BODY.get();
        OUTGOING_BODY.remove();
        return v;
    }

    public static void clearSafely() {
        try {
            OUTGOING_BODY.remove();
        } catch (Exception ignored) {
        }
    }
}


