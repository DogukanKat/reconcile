package dev.dogukankat.reconcile.notification.listener;

import org.springframework.stereotype.Component;

/**
 * Production no-op. Costs one virtual call per record; the JIT
 * eliminates it after warmup.
 */
@Component
public class NoOpListenerFaultInjector implements ListenerFaultInjector {

    @Override
    public void onRecord(String key) {
        // intentionally empty
    }
}
