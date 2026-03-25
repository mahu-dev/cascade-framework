package io.github.cascade.cache.v2.consistency;

/**
 * 版本管理器，负责 key 级版本和 clear 版本生成。
 */
public interface VersionManager<K> {

    long nextVersion(K key);

    long currentVersion(K key);

    long nextClearVersion();

    long currentClearVersion();
}
