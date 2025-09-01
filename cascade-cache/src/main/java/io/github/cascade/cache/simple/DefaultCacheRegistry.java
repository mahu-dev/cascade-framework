package io.github.cascade.cache.simple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 默认缓存注册中心实现
 * 基于ConcurrentHashMap，线程安全
 *
 * @author cascade
 */
//@Component
public class DefaultCacheRegistry implements CacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultCacheRegistry.class);

    /**
     * 缓存实例存储，使用泛型擦除来存储不同类型的缓存
     */
    private final ConcurrentMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    @Override
    public <K, V> boolean register(String name, Cache<K, V> cache) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (cache == null) {
            throw new IllegalArgumentException("缓存实例不能为空");
        }

        Cache<?, ?> existing = caches.putIfAbsent(name, cache);
        boolean registered = existing == null;

        if (registered) {
            log.info("缓存注册成功: name={}, type={}", name, cache.getClass().getSimpleName());
        } else {
            log.warn("缓存已存在，注册失败: name={}, existing={}", name, existing.getClass().getSimpleName());
        }

        return registered;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> get(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        Cache<?, ?> cache = caches.get(name);
        return cache != null ? (Cache<K, V>) cache : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> computeIfAbsent(String name, Supplier<Cache<K, V>> factory) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (factory == null) {
            throw new IllegalArgumentException("缓存工厂方法不能为空");
        }

        Cache<?, ?> cache = caches.computeIfAbsent(name, k -> {
            log.debug("创建新缓存实例: name={}", k);
            Cache<K, V> newCache = factory.get();
            log.info("新缓存创建完成: name={}, type={}", k, newCache.getClass().getSimpleName());
            return newCache;
        });

        return (Cache<K, V>) cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> remove(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        Cache<?, ?> removed = caches.remove(name);
        if (removed != null) {
            log.info("缓存已移除: name={}, type={}", name, removed.getClass().getSimpleName());

            // 尝试关闭缓存资源
            try {
                if (!removed.isClosed()) {
                    removed.close();
                    log.debug("缓存资源已关闭: name={}", name);
                }
            } catch (Exception e) {
                log.warn("关闭缓存资源失败: name={}, error={}", name, e.getMessage());
            }
        }

        return (Cache<K, V>) removed;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> replace(String name, Cache<K, V> cache) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }
        if (cache == null) {
            throw new IllegalArgumentException("缓存实例不能为空");
        }

        Cache<?, ?> previous = caches.replace(name, cache);
        if (previous != null) {
            log.info("缓存已替换: name={}, old={}, new={}", 
                     name, previous.getClass().getSimpleName(), cache.getClass().getSimpleName());
        } else {
            log.warn("替换缓存失败，缓存不存在: name={}", name);
        }

        return (Cache<K, V>) previous;
    }

    @Override
    public boolean contains(String name) {
        return name != null && caches.containsKey(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }

    @Override
    public int size() {
        return caches.size();
    }

    @Override
    public void clear() {
        if (caches.isEmpty()) {
            log.debug("缓存注册中心已为空，无需清理");
            return;
        }

        int sizeBefore = caches.size();
        log.info("开始清空缓存注册中心，当前缓存数: {}", sizeBefore);

        // 依次关闭所有缓存
        caches.values().forEach(cache -> {
            try {
                if (!cache.isClosed()) {
                    cache.close();
                    log.debug("缓存已关闭: name={}, type={}", cache.getName(), cache.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.warn("关闭缓存失败: name={}, error={}", cache.getName(), e.getMessage());
            }
        });

        caches.clear();
        log.info("缓存注册中心清空完成，清理了 {} 个缓存", sizeBefore);
    }

    @Override
    public boolean isEmpty() {
        return caches.isEmpty();
    }

    /**
     * 获取缓存统计信息
     *
     * @return 统计信息字符串
     */
    public String getStatsString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DefaultCacheRegistry{");
        sb.append("size=").append(size());
        sb.append(", caches=[");

        boolean first = true;
        for (String name : getCacheNames()) {
            if (!first) sb.append(", ");
            Cache<?, ?> cache = caches.get(name);
            sb.append(name).append(":").append(cache.getClass().getSimpleName());
            first = false;
        }

        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return getStatsString();
    }
}