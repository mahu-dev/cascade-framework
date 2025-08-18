package io.github.cascade.cache.event;

/**
 * 缓存事件监听器接口
 * 
 * @param <E> 事件类型
 * @author cascade
 */
@FunctionalInterface
public interface CacheEventListener<E extends CacheEvent> {
    
    /**
     * 处理缓存事件
     * 
     * @param event 缓存事件
     */
    void onEvent(E event);
    
    /**
     * 获取监听器名称
     * 
     * @return 监听器名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
    
    /**
     * 判断是否应该处理该事件
     * 
     * @param event 缓存事件
     * @return 是否处理
     */
    default boolean shouldHandle(E event) {
        return true;
    }
    
    /**
     * 监听器是否激活
     * 
     * @return 是否激活
     */
    default boolean isActive() {
        return true;
    }
    
    /**
     * 获取监听器优先级
     * 数值越小优先级越高
     * 
     * @return 优先级
     */
    default int getPriority() {
        return 0;
    }
}