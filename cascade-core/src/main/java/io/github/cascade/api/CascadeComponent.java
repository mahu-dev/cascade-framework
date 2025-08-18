package io.github.cascade.api;

import io.github.cascade.foundation.ComponentType;
import io.github.cascade.metrics.ComponentMetrics;

/**
 * Cascade组件基础接口
 * 所有功能模块都需要实现此接口
 */
public interface CascadeComponent extends Lifecycle, Identifiable, Observable {
    
    /**
     * 获取组件类型
     */
    ComponentType getType();
    
    /**
     * 获取组件名称
     */
    String getName();
    
    /**
     * 获取组件版本
     */
    String getVersion();
    
    /**
     * 获取组件描述
     */
    String getDescription();
    
    /**
     * 健康检查
     */
    HealthStatus health();
    
    /**
     * 获取组件配置
     */
    <T> T getConfiguration(Class<T> configType);
    
    /**
     * 重新加载配置
     */
    void reload(Object config);
    
    /**
     * 获取组件指标
     */
    ComponentMetrics getMetrics();
}