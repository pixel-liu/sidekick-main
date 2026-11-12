package com.sidekick.runtime;

import java.util.concurrent.atomic.AtomicReference;

public final class CancellationContext {
    private static final AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);
        return token;
    }

    /** Token for work launched by this run. Unrelated background requests have no token. */
    public static CancellationToken currentRun() {
        return LOCAL.get();
    }

    public static CancellationToken current() {
        CancellationToken token = currentRun();
        return token == null ? CURRENT.get() : token;
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
