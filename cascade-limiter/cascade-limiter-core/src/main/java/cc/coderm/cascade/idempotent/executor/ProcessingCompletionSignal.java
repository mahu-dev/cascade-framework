package cc.coderm.cascade.idempotent.executor;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 key 的本地处理完成信号。
 *
 * <p>线程安全性说明：
 * <ul>
 *   <li>{@code complete()} 方法通过 {@code AtomicBoolean.compareAndSet()} 保证只执行一次</li>
 *   <li>{@code CountDownLatch} 提供 happens-before 保证：countDown 前的写入对 await 返回后的读取可见</li>
 *   <li>因此 {@code terminalRecord} 字段不需要 volatile 修饰</li>
 * </ul>
 */
final class ProcessingCompletionSignal {

    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private IdempotentRecord terminalRecord;

    boolean await(long timeoutMs) throws InterruptedException {
        return done.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    IdempotentRecord getTerminalRecord() {
        return terminalRecord;
    }

    void complete(IdempotentRecord terminalRecord) {
        if (completed.compareAndSet(false, true)) {
            this.terminalRecord = terminalRecord;
            done.countDown();
        }
    }
}

