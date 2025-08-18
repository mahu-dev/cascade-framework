package io.github.cascade;

import io.github.cascade.api.CascadeComponent;
import io.github.cascade.api.HealthStatus;
import io.github.cascade.config.CascadeConfig;
import io.github.cascade.event.CascadeEvent;
import io.github.cascade.event.EventListener;
import io.github.cascade.exception.ComponentException;
import io.github.cascade.metrics.MetricsCollector;
import io.github.cascade.metrics.MetricsSnapshot;
import org.redisson.api.RedissonClient;

import java.util.Collection;

/**
 * Cascade框架统一入口门面
 * 提供所有功能模块的访问入口
 */
public class Cascade {
    
    private final CascadeContext context;
    private final CascadeRegistry registry;
    private final MetricsCollector metricsCollector;
    
    public Cascade(CascadeContext context, 
                  CascadeRegistry registry, 
                  MetricsCollector metricsCollector) {
        this.context = context;
        this.registry = registry;
        this.metricsCollector = metricsCollector;
    }
    
    /**
     * 获取组件的通用方法
     */
    public <T extends CascadeComponent> T getComponent(Class<T> componentType) {
        return registry.getRequiredComponent(componentType);
    }
    
    /**
     * 获取组件（通过名称）
     */
    public <T extends CascadeComponent> T getComponent(String name) {
        return registry.getRequiredComponent(name);
    }
    
    /**
     * 尝试获取组件（可能不存在）
     */
    public <T extends CascadeComponent> T getComponentOrNull(Class<T> componentType) {
        return registry.getComponent(componentType).orElse(null);
    }
    
    /**
     * 尝试获取组件（通过名称，可能不存在）
     */
    public <T extends CascadeComponent> T getComponentOrNull(String name) {
        return registry.<T>getComponent(name).orElse(null);
    }
    
    /**
     * 判断组件是否可用
     */
    public boolean hasComponent(Class<? extends CascadeComponent> componentType) {
        return registry.hasComponent(componentType);
    }
    
    /**
     * 判断组件是否可用（通过名称）
     */
    public boolean hasComponent(String name) {
        return registry.hasComponent(name);
    }
    
    /**
     * 获取所有组件
     */
    public Collection<CascadeComponent> getAllComponents() {
        return registry.getAllComponents();
    }
    
    /**
     * 获取全局上下文
     */
    public CascadeContext getContext() {
        return context;
    }
    
    /**
     * 获取 Redisson 客户端（高级用法）
     */
    public RedissonClient getRedissonClient() {
        return context.getRedissonClient();
    }
    
    /**
     * 全局配置
     */
    public CascadeConfig getConfig() {
        return context.getConfig();
    }
    
    /**
     * 发布事件
     */
    public void publishEvent(CascadeEvent event) {
        context.publishEvent(event);
    }
    
    /**
     * 订阅事件
     */
    public <E extends CascadeEvent> void subscribe(Class<E> eventType, EventListener<E> listener) {
        context.subscribe(eventType, listener);
    }
    
    /**
     * 取消订阅事件
     */
    public <E extends CascadeEvent> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        context.unsubscribe(eventType, listener);
    }
    
    /**
     * 健康检查
     */
    public HealthStatus health() {
        HealthStatus.Builder builder = HealthStatus.builder();
        
        boolean allHealthy = true;
        for (CascadeComponent component : registry.getAllComponents()) {
            HealthStatus componentHealth = component.health();
            builder.withComponent(component.getName(), componentHealth);
            
            if (componentHealth.getStatus() != HealthStatus.Status.UP) {
                allHealthy = false;
            }
        }
        
        if (allHealthy) {
            builder.up();
        } else {
            builder.down();
        }
        
        builder.withDetail("namespace", context.getNamespace())
               .withDetail("componentCount", registry.getComponentCount());
        
        return builder.build();
    }
    
    /**
     * 获取指标信息
     */
    public MetricsSnapshot getMetrics() {
        return metricsCollector != null ? metricsCollector.snapshot() : null;
    }
    
    /**
     * 启动所有组件
     */
    public void start() {
        for (CascadeComponent component : registry.getAllComponents()) {
            try {
                component.start();
            } catch (Exception e) {
                throw new ComponentException("Failed to start component: " + component.getName(), e);
            }
        }
    }
    
    /**
     * 停止所有组件
     */
    public void stop() {
        for (CascadeComponent component : registry.getAllComponents()) {
            try {
                component.stop();
            } catch (Exception e) {
                // 记录错误但继续停止其他组件
                System.err.println("Failed to stop component: " + component.getName() + ", error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        // 停止所有组件
        stop();
        
        // 关闭所有组件
        for (CascadeComponent component : registry.getAllComponents()) {
            try {
                component.close();
            } catch (Exception e) {
                // 记录错误但继续关闭其他组件
                System.err.println("Failed to close component: " + component.getName() + ", error: " + e.getMessage());
            }
        }
        
        // 关闭上下文
        context.close();
    }
}