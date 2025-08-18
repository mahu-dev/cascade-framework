package io.github.cascade.cache.api;

/**
 * Cascade缓存接口，扩展基础缓存功能并集成生命周期管理
 * 
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author cascade
 */
public interface CascadeCache<K, V> extends Cache<K, V> {
    
    /**
     * 初始化缓存
     * 
     * @throws Exception 初始化异常
     */
    void initialize() throws Exception;
    
    /**
     * 启动缓存
     * 
     * @throws Exception 启动异常
     */
    void start() throws Exception;
    
    /**
     * 停止缓存
     * 
     * @throws Exception 停止异常
     */
    void stop() throws Exception;
    
    /**
     * 关闭缓存，释放资源
     */
    void close();
    
    /**
     * 获取缓存运行状态
     * 
     * @return 生命周期状态
     */
    LifecycleState getState();
    
    /**
     * 检查缓存是否正在运行
     * 
     * @return 如果正在运行返回true
     */
    default boolean isRunning() {
        return getState() == LifecycleState.STARTED;
    }
    
    /**
     * 获取缓存类型描述
     * 
     * @return 缓存类型描述
     */
    String getCacheType();
    
    /**
     * 获取缓存配置摘要
     * 
     * @return 配置信息
     */
    String getConfigSummary();
    
    /**
     * 生命周期状态枚举
     */
    enum LifecycleState {
        /** 新创建状态 */
        NEW,
        /** 初始化中 */
        INITIALIZING,
        /** 已初始化 */
        INITIALIZED,
        /** 启动中 */
        STARTING,
        /** 已启动 */
        STARTED,
        /** 停止中 */
        STOPPING,
        /** 已停止 */
        STOPPED,
        /** 失败状态 */
        FAILED
    }
}