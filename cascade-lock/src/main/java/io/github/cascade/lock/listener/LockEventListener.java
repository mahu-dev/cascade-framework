package io.github.cascade.lock.listener;

/**
 * 锁事件监听器，实现此接口可监听锁的获取/释放/失败事件
 * <p>
 * 示例：
 * <pre>
 * {@literal @}Component
 * public class MyLockListener implements LockEventListener {
 *     {@literal @}Override
 *     public void onEvent(LockEvent event) {
 *         log.info("Lock event: {} - {}", event.getType(), event.getLockKey());
 *     }
 * }
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:03
 * =============================
 */
@FunctionalInterface
public interface LockEventListener {
    void onEvent(LockEvent event);
}
