package io.github.cascade.event;

/**
 * 事件监听器接口
 */
@FunctionalInterface
public interface EventListener<E extends CascadeEvent> {
    
    /**
     * 处理事件
     */
    void onEvent(E event);
}