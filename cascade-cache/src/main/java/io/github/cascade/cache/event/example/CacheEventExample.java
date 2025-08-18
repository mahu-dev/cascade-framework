package io.github.cascade.cache.event.example;

import io.github.cascade.cache.event.CacheEvent;
import io.github.cascade.cache.event.CacheEventListener;
import io.github.cascade.cache.event.CacheEventManager;
import io.github.cascade.cache.event.CacheOperationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 缓存事件使用示例
 * 展示如何创建自定义事件监听器并注册到事件管理器
 *
 * @author cascade
 */
@Component
public class CacheEventExample {

    private static final Logger logger = LoggerFactory.getLogger(CacheEventExample.class);

    @Autowired
    private CacheEventManager eventManager;

    @PostConstruct
    public void init() {
        // 注册自定义事件监听器
        eventManager.registerListener(new CustomCacheEventListener());
        
        logger.info("Cache event example initialized with custom listener");
    }

    /**
     * 自定义缓存事件监听器示例
     */
    public static class CustomCacheEventListener implements CacheEventListener<CacheOperationEvent> {

        private static final Logger logger = LoggerFactory.getLogger(CustomCacheEventListener.class);

        @Override
        public void onEvent(CacheOperationEvent event) {
            // 处理缓存命中事件
            if (event.getEventType() == CacheEvent.EventType.CACHE_HIT) {
                logger.debug("Cache hit for key: {} in cache: {}, duration: {}ms", 
                    event.getKey(), event.getCacheName(), event.getDuration().toMillis());
            }
            
            // 处理缓存未命中事件
            else if (event.getEventType() == CacheEvent.EventType.CACHE_MISS) {
                logger.debug("Cache miss for key: {} in cache: {}", 
                    event.getKey(), event.getCacheName());
            }
            
            // 处理缓存错误事件
            else if (event.getEventType() == CacheEvent.EventType.CACHE_ERROR) {
                logger.warn("Cache error for key: {} in cache: {}, error: {}", 
                    event.getKey(), event.getCacheName(), event.getException().getMessage());
            }
            
            // 处理慢操作
            if (event.getDuration() != null && event.getDuration().toMillis() > 100) {
                logger.warn("Slow cache operation detected: {} for key: {} in cache: {}, duration: {}ms",
                    event.getEventType(), event.getKey(), event.getCacheName(), event.getDuration().toMillis());
            }
        }

        @Override
        public String getName() {
            return "CustomCacheEventListener";
        }

        @Override
        public boolean shouldHandle(CacheOperationEvent event) {
            // 只处理缓存操作事件
            return event instanceof CacheOperationEvent;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public int getPriority() {
            return 100; // 中等优先级
        }
    }
}