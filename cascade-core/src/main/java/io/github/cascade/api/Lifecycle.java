package io.github.cascade.api;

/**
 * 组件生命周期管理
 */
public interface Lifecycle {
    
    enum State {
        NEW,
        INITIALIZING,
        INITIALIZED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }
    
    /**
     * 初始化
     */
    void initialize() throws Exception;
    
    /**
     * 启动
     */
    void start() throws Exception;
    
    /**
     * 停止
     */
    void stop() throws Exception;
    
    /**
     * 关闭
     */
    void close();
    
    /**
     * 获取当前状态
     */
    State getState();
    
    /**
     * 是否正在运行
     */
    default boolean isRunning() {
        return getState() == State.STARTED;
    }
}