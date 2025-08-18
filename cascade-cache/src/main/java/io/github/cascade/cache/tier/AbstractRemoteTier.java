package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.CacheTier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抽象远程缓存实现基类
 * 提供远程缓存的通用功能实现
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public abstract class AbstractRemoteTier<K, V> extends AbstractCache<K, V> implements RemoteTier<K, V> {
    
    // 配置参数
    protected volatile Duration defaultTtl;
    protected volatile String keyPrefix = "";
    protected volatile boolean enablePipeline = true;
    protected volatile boolean enableTransaction = true;
    protected volatile int batchSize = 100;
    
    // 连接统计
    protected final AtomicLong connectionErrors = new AtomicLong(0);
    protected final AtomicLong commandCount = new AtomicLong(0);
    protected final AtomicLong totalResponseTime = new AtomicLong(0);
    
    // 订阅管理
    protected final Map<String, SubscriptionImpl> subscriptions = new ConcurrentHashMap<>();
    
    protected AbstractRemoteTier(String name) {
        super(name, CacheTier.L2);
    }
    
    protected AbstractRemoteTier(String name, Duration defaultTtl) {
        super(name, CacheTier.L2);
        this.defaultTtl = defaultTtl;
    }
    
    // ==================== 配置管理 ====================
    
    /**
     * 获取默认TTL
     */
    protected Duration getDefaultTtl() {
        return defaultTtl;
    }
    
    /**
     * 设置默认TTL
     */
    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }
    
    /**
     * 获取键前缀
     */
    protected String getKeyPrefix() {
        return keyPrefix;
    }
    
    /**
     * 设置键前缀
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }
    
    // ==================== TTL管理默认实现 ====================
    
    @Override
    public void put(K key, V value) {
        put(key, value, defaultTtl);
    }
    
    @Override
    public void putAll(Map<K, V> map) {
        putAll(map, defaultTtl);
    }
    
    @Override
    public boolean putIfAbsent(K key, V value) {
        return putIfAbsent(key, value, defaultTtl);
    }
    
    // ==================== 模式匹配默认实现 ====================
    
    @Override
    public long deleteByPattern(String pattern) {
        try {
            Set<K> matchingKeys = keys(pattern);
            if (!matchingKeys.isEmpty()) {
                evictAll(matchingKeys);
                return matchingKeys.size();
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Error deleting keys by pattern: " + pattern, e);
        }
    }
    
    @Override
    public long countByPattern(String pattern) {
        try {
            return keys(pattern).size();
        } catch (Exception e) {
            throw new RuntimeException("Error counting keys by pattern: " + pattern, e);
        }
    }
    
    // ==================== 连接管理默认实现 ====================
    
    @Override
    public long ping() {
        try {
            long startTime = System.currentTimeMillis();
            boolean connected = isConnected();
            long responseTime = System.currentTimeMillis() - startTime;
            
            if (connected) {
                return responseTime;
            } else {
                return -1;
            }
        } catch (Exception e) {
            recordConnectionError();
            return -1;
        }
    }
    
    @Override
    public Map<String, String> getServerInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("connected", String.valueOf(isConnected()));
        info.put("ping", String.valueOf(ping()));
        info.put("command_count", String.valueOf(commandCount.get()));
        info.put("connection_errors", String.valueOf(connectionErrors.get()));
        if (totalResponseTime.get() > 0 && commandCount.get() > 0) {
            info.put("avg_response_time", String.valueOf(totalResponseTime.get() / commandCount.get()));
        }
        return info;
    }
    
    @Override
    public Map<String, Object> getMemoryInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("estimated_size", estimatedSize());
        info.put("tier", getTier().name());
        return info;
    }
    
    // ==================== 脚本执行默认实现 ====================
    
    @Override
    public Object evalSha(String scriptSha, List<K> keys, Object... args) {
        // 默认实现：如果脚本不存在，抛出异常
        if (!scriptExists(scriptSha)) {
            throw new RuntimeException("Script with SHA " + scriptSha + " does not exist");
        }
        // 子类应该重写此方法提供具体实现
        throw new UnsupportedOperationException("evalSha not implemented in " + getClass().getSimpleName());
    }
    
    @Override
    public String scriptLoad(String script) {
        // 默认实现：计算脚本的简单哈希
        return "sha1:" + Math.abs(script.hashCode());
    }
    
    @Override
    public boolean scriptExists(String scriptSha) {
        // 默认实现：假设脚本不存在
        return false;
    }
    
    // ==================== 管道和事务默认实现 ====================
    
    @Override
    public Pipeline pipeline() {
        if (!enablePipeline) {
            throw new UnsupportedOperationException("Pipeline is disabled");
        }
        return createPipeline();
    }
    
    @Override
    public Transaction transaction() {
        if (!enableTransaction) {
            throw new UnsupportedOperationException("Transaction is disabled");
        }
        return createTransaction();
    }
    
    // ==================== 发布订阅默认实现 ====================
    
    @Override
    public Subscription subscribe(MessageListener listener, String... channels) {
        SubscriptionImpl subscription = new SubscriptionImpl(listener, Arrays.asList(channels), false);
        for (String channel : channels) {
            subscriptions.put(channel, subscription);
        }
        doSubscribe(listener, channels);
        return subscription;
    }
    
    @Override
    public Subscription psubscribe(MessageListener listener, String... patterns) {
        SubscriptionImpl subscription = new SubscriptionImpl(listener, Arrays.asList(patterns), true);
        for (String pattern : patterns) {
            subscriptions.put(pattern, subscription);
        }
        doPsubscribe(listener, patterns);
        return subscription;
    }
    
    // ==================== 统计和工具方法 ====================
    
    /**
     * 记录连接错误
     */
    protected void recordConnectionError() {
        connectionErrors.incrementAndGet();
    }
    
    /**
     * 记录命令执行
     */
    protected void recordCommand(long responseTimeNanos) {
        commandCount.incrementAndGet();
        totalResponseTime.addAndGet(responseTimeNanos / 1_000_000); // 转换为毫秒
    }
    
    /**
     * 构建完整的键名（包含前缀）
     */
    protected String buildKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        return keyPrefix + key.toString();
    }
    
    /**
     * 从完整键名中提取原始键
     */
    @SuppressWarnings("unchecked")
    protected K extractKey(String fullKey) {
        if (fullKey == null) {
            return null;
        }
        if (fullKey.startsWith(keyPrefix)) {
            String originalKey = fullKey.substring(keyPrefix.length());
            // 简化实现，假设K是String类型
            return (K) originalKey;
        }
        return (K) fullKey;
    }
    
    // ==================== 抽象方法 ====================
    
    /**
     * 创建管道实现
     */
    protected abstract Pipeline createPipeline();
    
    /**
     * 创建事务实现
     */
    protected abstract Transaction createTransaction();
    
    /**
     * 执行订阅操作
     */
    protected abstract void doSubscribe(MessageListener listener, String... channels);
    
    /**
     * 执行模式订阅操作
     */
    protected abstract void doPsubscribe(MessageListener listener, String... patterns);
    
    // ==================== 内部类 ====================
    
    /**
     * 订阅实现
     */
    protected class SubscriptionImpl implements Subscription {
        private final MessageListener listener;
        private final List<String> channels;
        private final boolean isPattern;
        private volatile boolean active = true;
        
        public SubscriptionImpl(MessageListener listener, List<String> channels, boolean isPattern) {
            this.listener = listener;
            this.channels = new ArrayList<>(channels);
            this.isPattern = isPattern;
        }
        
        @Override
        public void unsubscribe() {
            if (active) {
                active = false;
                for (String channel : channels) {
                    subscriptions.remove(channel);
                }
                doUnsubscribe(channels.toArray(new String[0]));
            }
        }
        
        @Override
        public int getChannelCount() {
            return active ? channels.size() : 0;
        }
        
        @Override
        public boolean isActive() {
            return active;
        }
        
        public MessageListener getListener() {
            return listener;
        }
        
        public List<String> getChannels() {
            return new ArrayList<>(channels);
        }
        
        public boolean isPattern() {
            return isPattern;
        }
    }
    
    /**
     * 执行取消订阅操作
     */
    protected abstract void doUnsubscribe(String... channels);
}