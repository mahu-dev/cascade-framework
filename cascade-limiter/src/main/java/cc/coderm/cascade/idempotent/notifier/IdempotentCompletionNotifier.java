package cc.coderm.cascade.idempotent.notifier;

import java.util.concurrent.CompletableFuture;

/**
 * 幂等执行完成通知器：用于跨实例唤醒 WAIT 等待者。
 */
public interface IdempotentCompletionNotifier extends AutoCloseable {

    /**
     * 当前通知器是否启用。
     */
    boolean isEnabled();

    /**
     * 订阅指定幂等 key 的完成事件。
     */
    Subscription subscribe(String key);

    /**
     * 发布指定幂等 key 的完成事件。
     */
    void publish(String key);

    @Override
    default void close() {
    }

    interface Subscription extends AutoCloseable {
        CompletableFuture<Void> completion();

        @Override
        void close();
    }
}
