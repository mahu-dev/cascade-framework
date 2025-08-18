package io.github.cascade.cache.core.management;

import io.github.cascade.cache.core.properties.DistributedTieredCacheProperties;
import io.github.cascade.cache.protection.SimplifiedCacheProtectionManager;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 缓存加载管理器工厂
 * 负责创建CacheLoadingManager实例
 * 
 * @author cascade
 */
@Component
public class CacheLoadingManagerFactory implements ApplicationContextAware {
    
    private ApplicationContext applicationContext;
    private DistributedTieredCacheProperties properties;
    private SimplifiedCacheProtectionManager protectionManager;
    
    public CacheLoadingManagerFactory(DistributedTieredCacheProperties properties) {
        this.properties = properties;
    }
    
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        
        // 尝试获取防护管理器（可选）
        try {
            this.protectionManager = applicationContext.getBean(SimplifiedCacheProtectionManager.class);
        } catch (Exception e) {
            // 防护管理器是可选的，忽略异常
        }
    }
    
    /**
     * 创建CacheLoadingManager实例
     */
    public <K, V> CacheLoadingManager<K, V> createLoadingManager() {
        CacheLoadingManager<K, V> manager = new CacheLoadingManager<>(properties);
        if (protectionManager != null) {
            manager.setProtectionManager(protectionManager);
        }
        return manager;
    }
}