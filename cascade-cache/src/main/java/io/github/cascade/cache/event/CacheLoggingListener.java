package io.github.cascade.cache.event;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 缓存日志监听器
 * 记录缓存操作的详细日志信息
 * 
 * @author cascade
 */
public class CacheLoggingListener implements CacheEventListener<CacheOperationEvent> {
    
    private static final Logger logger = Logger.getLogger(CacheLoggingListener.class.getName());
    
    private volatile boolean enabled = true;
    private volatile Level logLevel = Level.INFO;
    private volatile boolean logDetails = false;
    private volatile boolean logErrors = true;
    private volatile boolean logSlowOperations = true;
    private volatile long slowOperationThreshold = 1000; // 1秒
    
    @Override
    public void onEvent(CacheOperationEvent event) {
        if (!enabled) {
            return;
        }
        
        try {
            switch (event.getEventType()) {
                case CACHE_HIT:
                    logCacheHit(event);
                    break;
                case CACHE_MISS:
                    logCacheMiss(event);
                    break;
                case CACHE_LOAD:
                    logCacheLoad(event);
                    break;
                case CACHE_PUT:
                    logCachePut(event);
                    break;
                case CACHE_EVICT:
                    logCacheEvict(event);
                    break;
                case CACHE_CLEAR:
                    logCacheClear(event);
                    break;
                case CACHE_ERROR:
                    logCacheError(event);
                    break;
                default:
                    if (logDetails) {
                        String message = String.format("Cache event: %s for cache [%s]", 
                                event.getEventType(), event.getCacheName());
                        logger.log(logLevel, message);
                    }
                    break;
            }
        } catch (Exception e) {
            // 避免日志记录异常影响缓存操作
            logger.log(Level.WARNING, "Error logging cache event: " + e.getMessage(), e);
        }
    }
    
    private void logCacheHit(CacheOperationEvent event) {
        if (shouldLogOperation(event)) {
            String message = String.format("Cache HIT: cache=[%s], key=[%s], duration=%dms",
                    event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis());
            logger.log(logLevel, message);
        }
    }
    
    private void logCacheMiss(CacheOperationEvent event) {
        if (shouldLogOperation(event)) {
            String message = String.format("Cache MISS: cache=[%s], key=[%s], duration=%dms",
                    event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis());
            logger.log(logLevel, message);
        }
    }
    
    private void logCacheLoad(CacheOperationEvent event) {
        if (event.isSuccess()) {
            if (shouldLogOperation(event)) {
                String message = String.format("Cache LOAD: cache=[%s], key=[%s], duration=%dms, success=true",
                        event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis());
                logger.log(logLevel, message);
            }
        } else {
            if (logErrors) {
                String message = String.format("Cache LOAD FAILED: cache=[%s], key=[%s], duration=%dms, error=%s",
                        event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis(),
                        event.getException() != null ? event.getException().getMessage() : "Unknown");
                logger.log(Level.WARNING, message, event.getException());
            }
        }
    }
    
    private void logCachePut(CacheOperationEvent event) {
        if (shouldLogOperation(event)) {
            String message = String.format("Cache PUT: cache=[%s], key=[%s], duration=%dms",
                    event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis());
            logger.log(logLevel, message);
        }
    }
    
    private void logCacheEvict(CacheOperationEvent event) {
        if (logDetails || isSlowOperation(event)) {
            String message = String.format("Cache EVICT: cache=[%s], key=[%s], duration=%dms",
                    event.getCacheName(), formatKey(event.getKey()), event.getDurationMillis());
            logger.log(logLevel, message);
        }
    }
    
    private void logCacheClear(CacheOperationEvent event) {
        String message = String.format("Cache CLEAR: cache=[%s], duration=%dms",
                event.getCacheName(), event.getDurationMillis());
        logger.log(Level.INFO, message);
    }
    
    private void logCacheError(CacheOperationEvent event) {
        if (logErrors) {
            String message = String.format("Cache ERROR: cache=[%s], key=[%s], error=%s",
                    event.getCacheName(), formatKey(event.getKey()),
                    event.getException() != null ? event.getException().getMessage() : "Unknown");
            logger.log(Level.SEVERE, message, event.getException());
        }
    }
    
    private boolean shouldLogOperation(CacheOperationEvent event) {
        return logDetails || isSlowOperation(event);
    }
    
    private boolean isSlowOperation(CacheOperationEvent event) {
        return logSlowOperations && event.getDurationMillis() >= slowOperationThreshold;
    }
    
    private String formatKey(Object key) {
        if (key == null) {
            return "null";
        }
        String keyStr = key.toString();
        // 限制键的长度以避免日志过长
        return keyStr.length() > 100 ? keyStr.substring(0, 97) + "..." : keyStr;
    }
    
    /**
     * 启用日志记录
     */
    public void enable() {
        this.enabled = true;
    }
    
    /**
     * 禁用日志记录
     */
    public void disable() {
        this.enabled = false;
    }
    
    /**
     * 设置日志级别
     * 
     * @param level 日志级别
     */
    public void setLogLevel(Level level) {
        this.logLevel = level;
    }
    
    /**
     * 设置是否记录详细信息
     * 
     * @param logDetails 是否记录详细信息
     */
    public void setLogDetails(boolean logDetails) {
        this.logDetails = logDetails;
    }
    
    /**
     * 设置是否记录错误
     * 
     * @param logErrors 是否记录错误
     */
    public void setLogErrors(boolean logErrors) {
        this.logErrors = logErrors;
    }
    
    /**
     * 设置是否记录慢操作
     * 
     * @param logSlowOperations 是否记录慢操作
     */
    public void setLogSlowOperations(boolean logSlowOperations) {
        this.logSlowOperations = logSlowOperations;
    }
    
    /**
     * 设置慢操作阈值
     * 
     * @param threshold 阈值（毫秒）
     */
    public void setSlowOperationThreshold(long threshold) {
        this.slowOperationThreshold = threshold;
    }
    
    /**
     * 获取日志级别
     */
    public Level getLogLevel() {
        return logLevel;
    }
    
    /**
     * 是否记录详细信息
     */
    public boolean isLogDetails() {
        return logDetails;
    }
    
    /**
     * 是否记录错误
     */
    public boolean isLogErrors() {
        return logErrors;
    }
    
    /**
     * 是否记录慢操作
     */
    public boolean isLogSlowOperations() {
        return logSlowOperations;
    }
    
    /**
     * 获取慢操作阈值
     */
    public long getSlowOperationThreshold() {
        return slowOperationThreshold;
    }
    
    @Override
    public boolean isActive() {
        return enabled;
    }
    
    @Override
    public String getName() {
        return "CacheLoggingListener";
    }
    
    @Override
    public int getPriority() {
        return 200; // 较低优先级，在统计监听器之后
    }
}