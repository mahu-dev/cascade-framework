package io.github.cascade.cache.v2.consistency;

/**
 * 版本管理器，负责生成缓存内单调递增的写入版本与 clear 版本。
 * <p>
 * {@code key} 参数用于对外 API 一致性与扩展位，当前实现可按需要选择“按 key”或“缓存级全局序列”策略。
 */
public interface VersionManager<K> {

    long nextVersion(K key);

    long currentVersion(K key);

    long nextClearVersion();

    long currentClearVersion();
}
