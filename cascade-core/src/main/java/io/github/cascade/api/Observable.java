package io.github.cascade.api;

import io.github.cascade.event.CascadeEvent;
import io.github.cascade.event.EventListener;

/**
 * 可观测接口
 */
public interface Observable {
    
    /**
     * 添加事件监听器
     */
    <E extends CascadeEvent> void addEventListener(Class<E> eventType, EventListener<E> listener);
    
    /**
     * 移除事件监听器
     */
    <E extends CascadeEvent> void removeEventListener(Class<E> eventType, EventListener<E> listener);
    
    /**
     * 发布事件
     */
    void publishEvent(CascadeEvent event);
}