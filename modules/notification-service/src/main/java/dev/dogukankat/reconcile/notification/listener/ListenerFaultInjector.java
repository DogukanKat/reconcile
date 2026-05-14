package dev.dogukankat.reconcile.notification.listener;

/**
 * Test seam for forcing the listener to throw on demand without
 * polluting the listener body with conditional logic. Production
 * registers a no-op singleton (the {@link NoOpListenerFaultInjector}
 * bean) so the call is free at runtime; the IT profile overrides
 * with a scripted implementation that throws on a specific
 * (record-key, attempt) combination.
 *
 * Keeping the interface in main code is a deliberate trade. The
 * alternative — Spring AOP around the listener method, or replacing
 * the listener bean in the test context — is more brittle and
 * harder for a reader to follow. A single-method interface invoked
 * inside the listener body is uglier but obvious.
 */
public interface ListenerFaultInjector {

    /**
     * Called once at the top of every {@code @KafkaListener} invocation
     * with the record key. Production no-op returns immediately; test
     * scripted impl may throw a {@link
     * dev.dogukankat.reconcile.notification.error.RetryableConsumerException}
     * or {@link
     * dev.dogukankat.reconcile.notification.error.NonRetryableConsumerException}.
     */
    void onRecord(String key);
}
