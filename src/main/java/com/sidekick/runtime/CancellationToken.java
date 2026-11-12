package com.sidekick.runtime;

import java.util.HashSet;
import java.util.Set;

public class CancellationToken {
    private volatile boolean cancelled;
    private final Set<Runnable> listeners = new HashSet<>();

    public void cancel() {
        Set<Runnable> callbacks;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            callbacks = Set.copyOf(listeners);
            listeners.clear();
        }
        callbacks.forEach(Runnable::run);
    }

    public Registration onCancel(Runnable callback) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(callback);
                return () -> remove(callback);
            }
        }
        callback.run();
        return () -> {};
    }

    private synchronized void remove(Runnable callback) {
        listeners.remove(callback);
    }

    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
