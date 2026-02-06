package io.bitdive.parent.trasirovka.agent.utils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal storage for outgoing Feign request body (original object before serialization).
 *
 * <p>Feign {@code Client.execute(Request,...)} sees only already serialized bytes.
 * The original DTO object is available earlier in {@code feign.codec.Encoder#encode(Object,...)}.
 *
 * <p>IMPORTANT: always clear to avoid ThreadLocal leaks.
 */
public final class OutgoingFeignBodyContext {

    private static final ThreadLocal<Deque<Object>> OUTGOING_BODIES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private OutgoingFeignBodyContext() {
    }

    public static void push(Object body) {
        if (body == null) return;
        OUTGOING_BODIES.get().offerLast(body);
    }

    /**
     * Returns last stored body and removes it from the stack.
     */
    public static Object pop() {
        Deque<Object> dq = OUTGOING_BODIES.get();
        Object v = dq.pollLast();
        if (dq.isEmpty()) {
            OUTGOING_BODIES.remove();
        }
        return v;
    }

    public static void clearSafely() {
        try {
            OUTGOING_BODIES.remove();
        } catch (Exception ignored) {
        }
    }
}

