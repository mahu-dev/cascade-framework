package io.github.cascade.cache.annotation.processor;

import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.core.CacheLoaderResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存加载器注册表
 * 自动发现并注册实现了CacheLoader接口的Spring Bean
 * 与CacheLoaderResolver集成，提供统一的加载器管理
 * <p>
 * 使用延迟初始化策略避免循环依赖：
 * - 构造器中不立即扫描Bean
 * - 监听ContextRefreshedEvent，在容器完全初始化后执行扫描
 *
 * @author cascade
 */
@Slf4j
public class CacheLoaderRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final ApplicationContext applicationContext;
    private final CacheLoaderResolver cacheLoaderResolver;
    private volatile boolean initialized = false;
    private final Map<String, CacheLoader<?, ?>> loaderRegistry = new ConcurrentHashMap<>();

    public CacheLoaderRegistry(ApplicationContext applicationContext, CacheLoaderResolver cacheLoaderResolver) {
        this.applicationContext = applicationContext;
        this.cacheLoaderResolver = cacheLoaderResolver;
        // 不在构造器中立即扫描，避免循环依赖
        log.debug("缓存加载器注册表已创建，将在上下文刷新后扫描CacheLoader实现");
    }

    /**
     * 监听ContextRefreshedEvent，在Spring容器完全初始化后执行扫描
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 确保只执行一次扫描
        if (!initialized && event.getApplicationContext() == this.applicationContext) {
            synchronized (this) {
                if (!initialized) {
                    log.info("上下文已刷新，开始扫描CacheLoader实现...");
                    scanAndRegisterCacheLoaders();
                    initialized = true;
                    log.info("CacheLoader扫描完成，已注册 {} 个加载器", loaderRegistry.size());
                }
            }
        }
    }

    /**
     * 扫描所有实现了CacheLoader接口的Spring Bean并注册
     */
    private void scanAndRegisterCacheLoaders() {
        @SuppressWarnings("unchecked")
        Map<String, CacheLoader<?, ?>> loaderBeans = (Map<String, CacheLoader<?, ?>>) (Map<?, ?>) applicationContext.getBeansOfType(CacheLoader.class);
        
        for (Map.Entry<String, CacheLoader<?, ?>> entry : loaderBeans.entrySet()) {
            String beanName = entry.getKey();
            CacheLoader<?, ?> loader = entry.getValue();
            
            try {
                registerCacheLoader(beanName, loader);
                log.debug("已注册缓存加载器: {} -> {}", beanName, loader.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("注册缓存加载器Bean '{}' 时失败: {}", beanName, e.getMessage());
            }
        }

        log.info("从Spring容器中发现并注册了 {} 个CacheLoader实现", loaderBeans.size());
    }

    /**
     * 注册缓存加载器到内部注册表
     */
    private void registerCacheLoader(String beanName, CacheLoader<?, ?> loader) {
        // 使用Bean名称注册
        loaderRegistry.put(beanName, loader);
        
        // 使用加载器自身的名称注册（如果不同的话）
        String loaderName = loader.getName();
        if (!beanName.equals(loaderName) && !loaderRegistry.containsKey(loaderName)) {
            loaderRegistry.put(loaderName, loader);
            log.debug("使用加载器名称 '{}' 额外注册了缓存加载器", loaderName);
        }
        
        log.debug("已注册缓存加载器: {} -> {}", beanName, loader.getClass().getSimpleName());
    }

    /**
     * 获取指定名称的缓存加载器
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> getLoader(String name) {
        // 先从内部注册表查找
        CacheLoader<?, ?> loader = loaderRegistry.get(name);
        if (loader != null) {
            return (CacheLoader<K, V>) loader;
        }
        
        // 如果还没有完成初始化扫描，尝试直接从Spring容器获取
        if (!initialized) {
            log.debug("缓存加载器注册表尚未初始化，尝试直接从容器查找: {}", name);
            try {
                if (applicationContext.containsBean(name)) {
                    Object bean = applicationContext.getBean(name);
                    if (bean instanceof CacheLoader) {
                        return (CacheLoader<K, V>) bean;
                    }
                }
            } catch (Exception e) {
                log.debug("获取加载器Bean '{}' 失败: {}", name, e.getMessage());
            }
        }
        
        return null;
    }

    /**
     * 根据缓存名称获取加载器 - 委托给CacheLoaderResolver
     */
    @SuppressWarnings("unchecked")
    public <K, V> CacheLoader<K, V> getLoaderForCache(String cacheName) {
        if (!initialized) {
            log.debug("缓存加载器注册表尚未初始化，委托给CacheLoaderResolver处理缓存: {}", cacheName);
        }
        return cacheLoaderResolver.resolveCacheLoader(cacheName, (Class<K>) Object.class, (Class<V>) Object.class);
    }

    /**
     * 获取所有已注册的加载器
     */
    public Map<String, CacheLoader<?, ?>> getAllLoaders() {
        return Map.copyOf(loaderRegistry);
    }
    
    /**
     * 检查是否包含指定名称的加载器
     */
    public boolean containsLoader(String name) {
        return loaderRegistry.containsKey(name);
    }

}