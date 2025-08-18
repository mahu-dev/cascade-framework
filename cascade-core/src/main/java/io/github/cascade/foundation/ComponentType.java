package io.github.cascade.foundation;

/**
 * 组件类型枚举
 */
public enum ComponentType {
    CACHE("缓存"),
    LOCK("锁"),
    BLOOM("布隆过滤器"),
    LIMITER("限流器"),
    QUEUE("队列"),
    PUBSUB("发布订阅"),
    UNKNOWN("未知");
    
    private final String description;
    
    ComponentType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}