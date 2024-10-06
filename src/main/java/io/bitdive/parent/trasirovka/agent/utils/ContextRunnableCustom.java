package io.bitdive.parent.trasirovka.agent.utils;

import io.bitdive.parent.dto.TraceMethodContext;

public class ContextRunnableCustom implements Runnable {
    private final Runnable originalRunnable;
    private final TraceMethodContext context;

    public ContextRunnableCustom(Runnable originalRunnable, TraceMethodContext context) {
        this.originalRunnable = originalRunnable;
        this.context = context;
    }

    @Override
    public void run() {
        ContextManager.setContextThread(context);
        originalRunnable.run();
    }
}
