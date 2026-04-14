package cc.coderm.cascade.idempotent.notifier;

import java.util.concurrent.CompletableFuture;

/**
 * 禁用态通知器：不提供跨实例通知能力。
 */
public final class NoopIdempotentCompletionNotifier implements IdempotentCompletionNotifier {

    public static final NoopIdempotentCompletionNotifier INSTANCE = new NoopIdempotentCompletionNotifier();

    private static final Subscription NOOP_SUBSCRIPTION = new Subscription() {
        private final CompletableFuture<Void> never = new CompletableFuture<>();

        @Override
        public CompletableFuture<Void> completion() {
            return never;
        }

        @Override
        public void close() {
        }
    };

    private NoopIdempotentCompletionNotifier() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public Subscription subscribe(String key) {
        return NOOP_SUBSCRIPTION;
    }

    @Override
    public void publish(String key) {
    }
}
