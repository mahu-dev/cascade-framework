package io.github.cascade.cache.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * 缓存事件发布器
 * 负责管理事件监听器和发布事件
 * 
 * @author cascade
 */
public class CacheEventPublisher {
    
    private final List<CacheEventListener<CacheEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private volatile boolean enabled = true;
    
    public CacheEventPublisher() {
        this(ForkJoinPool.commonPool());
    }
    
    public CacheEventPublisher(Executor executor) {
        this.executor = executor;
    }
    
    /**
     * 注册事件监听器
     * 
     * @param listener 监听器
     */
    @SuppressWarnings("unchecked")
    public void registerListener(CacheEventListener<? extends CacheEvent> listener) {
        if (listener != null) {
            listeners.add((CacheEventListener<CacheEvent>) listener);
            // 按优先级排序
            listeners.sort((l1, l2) -> Integer.compare(l1.getPriority(), l2.getPriority()));
        }
    }
    
    /**
     * 移除事件监听器
     * 
     * @param listener 监听器
     */
    public void removeListener(CacheEventListener<? extends CacheEvent> listener) {
        listeners.remove(listener);
    }
    
    /**
     * 发布事件
     * 
     * @param event 事件
     */
    public void publishEvent(CacheEvent event) {
        if (!enabled || event == null) {
            return;
        }
        
        // 获取激活的监听器
        List<CacheEventListener<CacheEvent>> activeListeners = listeners.stream()
                .filter(CacheEventListener::isActive)
                .filter(listener -> listener.shouldHandle(event))
                .collect(Collectors.toList());
        
        if (activeListeners.isEmpty()) {
            return;
        }
        
        // 异步发布事件
        executor.execute(() -> {
            for (CacheEventListener<CacheEvent> listener : activeListeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    // 记录异常但不影响其他监听器
                    handleListenerException(listener, event, e);
                }
            }
        });
    }
    
    /**
     * 同步发布事件
     * 
     * @param event 事件
     */
    public void publishEventSync(CacheEvent event) {
        if (!enabled || event == null) {
            return;
        }
        
        for (CacheEventListener<CacheEvent> listener : listeners) {
            if (listener.isActive() && listener.shouldHandle(event)) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    handleListenerException(listener, event, e);
                }
            }
        }
    }
    
    /**
     * 获取所有监听器
     * 
     * @return 监听器列表
     */
    public List<CacheEventListener<CacheEvent>> getListeners() {
        return List.copyOf(listeners);
    }
    
    /**
     * 获取激活的监听器数量
     * 
     * @return 激活监听器数量
     */
    public int getActiveListenerCount() {
        return (int) listeners.stream()
                .filter(CacheEventListener::isActive)
                .count();
    }
    
    /**
     * 清空所有监听器
     */
    public void clearListeners() {
        listeners.clear();
    }
    
    /**
     * 启用事件发布
     */
    public void enable() {
        this.enabled = true;
    }
    
    /**
     * 禁用事件发布
     */
    public void disable() {
        this.enabled = false;
    }
    
    /**
     * 是否启用
     * 
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 处理监听器异常
     * 
     * @param listener 监听器
     * @param event 事件
     * @param exception 异常
     */
    protected void handleListenerException(CacheEventListener<CacheEvent> listener, 
                                         CacheEvent event, Exception exception) {
        // 默认实现：记录异常信息
        System.err.printf("Error in cache event listener [%s] handling event [%s]: %s%n",
                listener.getName(), event.getEventType(), exception.getMessage());
    }
}