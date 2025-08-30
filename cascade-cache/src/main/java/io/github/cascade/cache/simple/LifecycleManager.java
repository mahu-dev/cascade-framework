package io.github.cascade.cache.simple;

import java.util.Collection;

/**
 * 生命周期管理器接口
 * 负责管理各种组件（缓存刷新器、同步器等）的生命周期
 * 
 * @author cascade
 */
public interface LifecycleManager {
    
    /**
     * 启动组件
     * 
     * @param name 组件名称
     * @param component 组件实例
     * @param <T> 组件类型
     */
    <T> void start(String name, T component);
    
    /**
     * 停止指定名称的组件
     * 
     * @param name 组件名称
     * @return 是否成功停止
     */
    boolean stop(String name);
    
    /**
     * 停止所有组件
     */
    void stopAll();
    
    /**
     * 检查指定组件是否正在运行
     * 
     * @param name 组件名称
     * @return 是否正在运行
     */
    boolean isRunning(String name);
    
    /**
     * 获取指定名称的组件
     * 
     * @param name 组件名称
     * @param <T> 组件类型
     * @return 组件实例，不存在时返回null
     */
    <T> T getComponent(String name);
    
    /**
     * 获取所有正在运行的组件名称
     * 
     * @return 组件名称集合
     */
    Collection<String> getRunningComponentNames();
    
    /**
     * 获取正在运行的组件总数
     * 
     * @return 组件总数
     */
    int getRunningCount();
    
    /**
     * 检查生命周期管理器是否已关闭
     * 
     * @return 是否已关闭
     */
    boolean isClosed();
    
    /**
     * 关闭生命周期管理器
     */
    void close();
}