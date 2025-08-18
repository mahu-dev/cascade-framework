package io.github.cascade.cache.sync;

import io.github.cascade.cache.api.Cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存同步消息处理器
 * 负责处理接收到的同步消息并更新本地缓存
 *
 * @author cascade
 */
public class CacheSyncMessageHandler implements SyncMessageListener {
    
    private final Map<String, Cache<Object, Object>> cacheRegistry = new ConcurrentHashMap<>();
    private final String handlerName;
    
    /**
     * 构造函数
     */
    public CacheSyncMessageHandler() {
        this("CacheSyncMessageHandler");
    }
    
    /**
     * 构造函数
     *
     * @param handlerName 处理器名称
     */
    public CacheSyncMessageHandler(String handlerName) {
        this.handlerName = handlerName;
    }
    
    /**
     * 注册缓存实例
     *
     * @param cacheName 缓存名称
     * @param cache 缓存实例
     */
    public void registerCache(String cacheName, Cache<Object, Object> cache) {
        cacheRegistry.put(cacheName, cache);
    }
    
    /**
     * 注销缓存实例
     *
     * @param cacheName 缓存名称
     */
    public void unregisterCache(String cacheName) {
        cacheRegistry.remove(cacheName);
    }
    
    /**
     * 获取注册的缓存实例
     *
     * @param cacheName 缓存名称
     * @return 缓存实例，如果不存在则返回null
     */
    public Cache<Object, Object> getCache(String cacheName) {
        return cacheRegistry.get(cacheName);
    }
    
    /**
     * 处理同步消息
     */
    @Override
    public void onMessage(SyncMessage message) {
        if (message == null || message.getCacheName() == null) {
            return;
        }
        
        Cache<Object, Object> cache = cacheRegistry.get(message.getCacheName());
        if (cache == null) {
            // 缓存不存在，忽略消息
            return;
        }
        
        try {
            switch (message.getType()) {
                case INVALIDATE:
                    handleInvalidate(cache, message);
                    break;
                case INVALIDATE_ALL:
                    handleInvalidateAll(cache, message);
                    break;
                case CLEAR:
                    handleClear(cache, message);
                    break;
                case UPDATE:
                    handleUpdate(cache, message);
                    break;
                default:
                    // 未知消息类型，忽略
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error handling sync message: " + message + ", error: " + e.getMessage());
        }
    }
    
    /**
     * 处理失效消息
     */
    private void handleInvalidate(Cache<Object, Object> cache, SyncMessage message) {
        if (message.getKey() != null) {
            cache.evict(message.getKey());
        }
    }
    
    /**
     * 处理批量失效消息
     */
    private void handleInvalidateAll(Cache<Object, Object> cache, SyncMessage message) {
        // 对于批量失效，key字段可能包含多个键的信息
        // 这里简化处理，实际实现可能需要更复杂的逻辑
        if (message.getKey() != null) {
            cache.evict(message.getKey());
        }
    }
    
    /**
     * 处理清空消息
     */
    private void handleClear(Cache<Object, Object> cache, SyncMessage message) {
        cache.clear();
    }
    
    /**
     * 处理更新消息
     */
    private void handleUpdate(Cache<Object, Object> cache, SyncMessage message) {
        if (message.getKey() != null && message.getValue() != null) {
            cache.put(message.getKey(), message.getValue());
        }
    }
    
    @Override
    public String getName() {
        return handlerName;
    }
    
    @Override
    public boolean shouldHandle(SyncMessage message) {
        // 只处理已注册缓存的消息
        return message != null && 
               message.getCacheName() != null && 
               cacheRegistry.containsKey(message.getCacheName());
    }
    
    @Override
    public void onMessageSent(SyncMessage message) {
        // 可以在这里记录发送成功的日志
    }
    
    @Override
    public void onMessageSendFailed(SyncMessage message, Throwable throwable) {
        System.err.println("Failed to send sync message: " + message + ", error: " + throwable.getMessage());
    }
    
    /**
     * 获取注册的缓存数量
     *
     * @return 缓存数量
     */
    public int getCacheCount() {
        return cacheRegistry.size();
    }
    
    /**
     * 获取所有注册的缓存名称
     *
     * @return 缓存名称集合
     */
    public java.util.Set<String> getCacheNames() {
        return cacheRegistry.keySet();
    }
    
    /**
     * 清空所有注册的缓存
     */
    public void clearAllCaches() {
        cacheRegistry.clear();
    }
}