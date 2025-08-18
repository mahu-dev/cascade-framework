package io.github.cascade;

import io.github.cascade.config.CascadeConfig;
import io.github.cascade.event.CascadeEvent;
import io.github.cascade.event.EventBus;
import io.github.cascade.event.EventListener;
import io.github.cascade.serializer.Serializer;
import io.github.cascade.serializer.SerializerFactory;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cascade全局上下文
 * 管理全局状态和共享资源
 */
public class CascadeContext implements AutoCloseable {
    
    private final String namespace;
    private final RedissonClient redissonClient;
    private final CascadeConfig config;
    private final SerializerFactory serializerFactory;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    public CascadeContext(String namespace,
                         RedissonClient redissonClient,
                         CascadeConfig config,
                         SerializerFactory serializerFactory,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler) {
        this.namespace = namespace;
        this.redissonClient = redissonClient;
        this.config = config;
        this.serializerFactory = serializerFactory;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
    }
    
    /**
     * 获取命名空间
     */
    public String getNamespace() {
        return namespace;
    }
    
    /**
     * 获取带命名空间的键
     */
    public String getNamespacedKey(String key) {
        return namespace != null && !namespace.isEmpty() 
            ? namespace + ":" + key 
            : key;
    }
    
    /**
     * 获取Redisson客户端
     */
    public RedissonClient getRedissonClient() {
        return redissonClient;
    }
    
    /**
     * 获取配置
     */
    public CascadeConfig getConfig() {
        return config;
    }
    
    /**
     * 获取默认序列化器
     */
    public Serializer getSerializer() {
        return serializerFactory.getDefault();
    }
    
    /**
     * 获取指定类型的序列化器
     */
    public Serializer getSerializer(String type) {
        return serializerFactory.get(type);
    }
    
    /**
     * 获取序列化器工厂
     */
    public SerializerFactory getSerializerFactory() {
        return serializerFactory;
    }
    
    /**
     * 获取事件总线
     */
    public EventBus getEventBus() {
        return eventBus;
    }
    
    /**
     * 发布事件
     */
    public void publishEvent(CascadeEvent event) {
        eventBus.publish(event);
    }
    
    /**
     * 订阅事件
     */
    public <E extends CascadeEvent> void subscribe(Class<E> eventType, EventListener<E> listener) {
        eventBus.subscribe(eventType, listener);
    }
    
    /**
     * 取消订阅事件
     */
    public <E extends CascadeEvent> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        eventBus.unsubscribe(eventType, listener);
    }
    
    /**
     * 调度任务
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }
    
    /**
     * 调度任务
     */
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * 周期性调度任务
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, 
                                                 long initialDelay, 
                                                 long period, 
                                                 TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }
    
    /**
     * 周期性调度任务
     */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, 
                                                 Duration initialDelay, 
                                                 Duration period) {
        return scheduler.scheduleAtFixedRate(task, 
                                           initialDelay.toMillis(), 
                                           period.toMillis(), 
                                           TimeUnit.MILLISECONDS);
    }
    
    /**
     * 是否已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
            }
        }
    }
}