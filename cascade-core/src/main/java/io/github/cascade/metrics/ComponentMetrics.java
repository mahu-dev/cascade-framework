package io.github.cascade.metrics;

import java.util.Map;

/**
 * 组件指标接口
 */
public interface ComponentMetrics {
    
    /**
     * 获取组件名称
     */
    String getComponentName();
    
    /**
     * 获取指标数据
     */
    Map<String, Object> getMetrics();
    
    /**
     * 重置指标
     */
    void reset();
}