package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.dto.TraceMethodContext;

import java.util.concurrent.Callable;

public class ContextCallableCustom<V> implements Callable<V> {
    private final Callable<V> originalCallable;
    private final TraceMethodContext context;

    public ContextCallableCustom(Callable<V> originalCallable, TraceMethodContext context) {
        this.originalCallable = originalCallable;
        this.context = context;
    }

    @Override
    public V call() throws Exception {
        ContextManager.setContextThread(context);
        return originalCallable.call();
    }
}
