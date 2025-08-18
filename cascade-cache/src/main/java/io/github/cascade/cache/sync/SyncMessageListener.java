package io.github.cascade.cache.sync;

/**
 * 同步消息监听器接口
 *
 * @author cascade
 */
public interface SyncMessageListener {
    
    /**
     * 处理接收到的同步消息
     *
     * @param message 同步消息
     */
    void onMessage(SyncMessage message);
    
    /**
     * 处理消息发送成功事件
     *
     * @param message 同步消息
     */
    default void onMessageSent(SyncMessage message) {
        // 默认空实现
    }
    
    /**
     * 处理消息发送失败事件
     *
     * @param message 同步消息
     * @param throwable 异常信息
     */
    default void onMessageSendFailed(SyncMessage message, Throwable throwable) {
        // 默认空实现
    }
    
    /**
     * 获取监听器名称
     *
     * @return 监听器名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 检查是否应该处理此消息
     *
     * @param message 同步消息
     * @return 是否应该处理
     */
    default boolean shouldHandle(SyncMessage message) {
        return true;
    }
}