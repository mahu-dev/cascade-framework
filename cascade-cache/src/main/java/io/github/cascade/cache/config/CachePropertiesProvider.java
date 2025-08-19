package io.github.cascade.cache.config;

/**
 * 缓存属性提供者接口
 * 用于从配置文件向运行时配置转换，避免模块间循环依赖
 *
 * @author Cascade Framework
 */
public interface CachePropertiesProvider {

    /**
     * 转换为运行时配置对象
     *
     * @param cacheName 缓存名称
     * @return 配置对象
     */
    CascadeCacheConfiguration toCascadeCacheConfiguration(String cacheName);

    /**
     * 检查是否启用
     */
    boolean isEnabled();
}