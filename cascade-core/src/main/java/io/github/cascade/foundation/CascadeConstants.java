package io.github.cascade.foundation;

/**
 * Cascade框架常量定义
 */
public final class CascadeConstants {
    
    private CascadeConstants() {
        // 工具类，不允许实例化
    }
    
    /** 默认命名空间 */
    public static final String DEFAULT_NAMESPACE = "cascade";
    
    /** 配置前缀 */
    public static final String CONFIG_PREFIX = "cascade";
    
    /** 默认序列化器 */
    public static final String DEFAULT_SERIALIZER = "json";
    
    /** 默认编解码器 */
    public static final String DEFAULT_CODEC = "string";
    
    /** 缓存相关常量 */
    public static final class Cache {
        public static final String DEFAULT_CACHE_NAME = "default";
        public static final long DEFAULT_MAXIMUM_SIZE = 10000L;
        public static final long DEFAULT_TTL_SECONDS = 3600L;
        public static final String SYNC_TOPIC_PREFIX = "cascade:cache:sync:";
        
        private Cache() {}
    }
    
    /** 锁相关常量 */
    public static final class Lock {
        public static final long DEFAULT_WAIT_TIME_SECONDS = 5L;
        public static final long DEFAULT_LEASE_TIME_SECONDS = 30L;
        public static final String LOCK_KEY_PREFIX = "cascade:lock:";
        
        private Lock() {}
    }
    
    /** 布隆过滤器相关常量 */
    public static final class Bloom {
        public static final long DEFAULT_EXPECTED_INSERTIONS = 1000000L;
        public static final double DEFAULT_FALSE_POSITIVE_PROBABILITY = 0.01;
        public static final String BLOOM_KEY_PREFIX = "cascade:bloom:";
        
        private Bloom() {}
    }
    
    /** 限流器相关常量 */
    public static final class Limiter {
        public static final long DEFAULT_RATE = 100L;
        public static final long DEFAULT_INTERVAL_SECONDS = 1L;
        public static final String LIMITER_KEY_PREFIX = "cascade:limiter:";
        
        private Limiter() {}
    }
    
    /** 队列相关常量 */
    public static final class Queue {
        public static final int DEFAULT_BATCH_SIZE = 100;
        public static final long DEFAULT_POLL_TIMEOUT_SECONDS = 5L;
        public static final String QUEUE_KEY_PREFIX = "cascade:queue:";
        
        private Queue() {}
    }
    
    /** 发布订阅相关常量 */
    public static final class PubSub {
        public static final String TOPIC_PREFIX = "cascade:topic:";
        
        private PubSub() {}
    }
}