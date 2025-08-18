package io.github.cascade.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * 事件总线
 */
public class EventBus {
    
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final boolean async;
    
    public EventBus() {
        this(ForkJoinPool.commonPool(), true);
    }
    
    public EventBus(ExecutorService executor, boolean async) {
        this.executor = executor;
        this.async = async;
    }
    
    /**
     * 发布事件
     */
    @SuppressWarnings("unchecked")
    public void publish(CascadeEvent event) {
        List<EventListener<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners != null && !eventListeners.isEmpty()) {
            Runnable task = () -> {
                for (EventListener<?> listener : eventListeners) {
                    try {
                        ((EventListener<CascadeEvent>) listener).onEvent(event);
                    } catch (Exception e) {
                        System.err.println("Event listener error: " + e.getMessage());
                    }
                }
            };
            
            if (async) {
                executor.execute(task);
            } else {
                task.run();
            }
        }
    }
    
    /**
     * 订阅事件
     */
    @SuppressWarnings("unchecked")
    public <E extends CascadeEvent> void subscribe(Class<E> eventType, EventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                 .add((EventListener<?>) listener);
    }
    
    /**
     * 取消订阅
     */
    @SuppressWarnings("unchecked")
    public <E extends CascadeEvent> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        List<EventListener<?>> eventListeners = listeners.get(eventType);
        if (eventListeners != null) {
            eventListeners.remove((EventListener<?>) listener);
        }
    }
    
    /**
     * 清除所有监听器
     */
    public void clear() {
        listeners.clear();
    }
    
    /**
     * 获取监听器数量
     */
    public int getListenerCount(Class<? extends CascadeEvent> eventType) {
        List<EventListener<?>> eventListeners = listeners.get(eventType);
        return eventListeners != null ? eventListeners.size() : 0;
    }
}