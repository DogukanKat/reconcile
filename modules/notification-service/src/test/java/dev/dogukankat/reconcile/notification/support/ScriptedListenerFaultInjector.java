package dev.dogukankat.reconcile.notification.support;

import dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException;
import dev.dogukankat.reconcile.notification.error.RetryableConsumerException;
import dev.dogukankat.reconcile.notification.listener.ListenerFaultInjector;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-side {@link ListenerFaultInjector} that keys behaviour by
 * record key. A scenario calls {@link #scriptFor(String, List)} with
 * a sequence of outcomes; the listener walks the sequence on each
 * attempt against that key. Records whose key is not scripted
 * pass through with no fault — important because real Debezium
 * events may still be flowing into the same topic during the test.
 */
public class ScriptedListenerFaultInjector implements ListenerFaultInjector {

    public enum Outcome {
        SUCCEED,
        RETRYABLE,
        NON_RETRYABLE
    }

    private final ConcurrentMap<String, List<Outcome>> scripts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public void scriptFor(String key, List<Outcome> outcomes) {
        scripts.put(key, List.copyOf(outcomes));
        attempts.put(key, new AtomicInteger());
    }

    public int attemptsFor(String key) {
        AtomicInteger counter = attempts.get(key);
        return counter == null ? 0 : counter.get();
    }

    public void reset() {
        scripts.clear();
        attempts.clear();
    }

    @Override
    public void onRecord(String key) {
        List<Outcome> script = scripts.get(key);
        if (script == null) {
            return;
        }
        int n = attempts.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement();
        if (n >= script.size()) {
            return; // script exhausted; subsequent attempts succeed
        }
        switch (script.get(n)) {
            case SUCCEED -> { /* no-op */ }
            case RETRYABLE -> throw new RetryableConsumerException(
                    "scripted retryable attempt=" + n + " key=" + key,
                    new RuntimeException("synthetic"));
            case NON_RETRYABLE -> throw new NonRetryableConsumerException(
                    "scripted non-retryable attempt=" + n + " key=" + key);
        }
    }
}
