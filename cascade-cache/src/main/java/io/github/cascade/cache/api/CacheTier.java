package io.github.cascade.cache.api;

/**
 * 缓存层级枚举
 *
 * @author cascade
 */
public enum CacheTier {

    /**
     * L1缓存层（本地缓存，如Caffeine）
     */
    L1("L1", 1, "本地缓存层"),

    /**
     * L2缓存层（远程缓存，如Redis）
     */
    L2("L2", 2, "远程缓存层"),

    /**
     * L3缓存层（持久化缓存，如数据库）
     */
    L3("L3", 3, "持久化缓存层"),

    /**
     * 多级缓存层（组合多个层级）
     */
    MULTI_LEVEL("MULTI", 0, "多级缓存层");

    private final String name;
    private final int level;
    private final String description;

    CacheTier(String name, int level, String description) {
        this.name = name;
        this.level = level;
        this.description = description;
    }

    /**
     * 获取层级名称
     *
     * @return 层级名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取层级级别
     *
     * @return 层级级别
     */
    public int getLevel() {
        return level;
    }

    /**
     * 获取层级描述
     *
     * @return 层级描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断是否为本地缓存层
     *
     * @return 如果是本地缓存层返回true
     */
    public boolean isLocal() {
        return this == L1;
    }

    /**
     * 判断是否为远程缓存层
     *
     * @return 如果是远程缓存层返回true
     */
    public boolean isRemote() {
        return this == L2 || this == L3;
    }

    /**
     * 判断是否比指定层级更高
     *
     * @param other 其他层级
     * @return 如果更高返回true
     */
    public boolean isHigherThan(CacheTier other) {
        return this.level < other.level;
    }

    /**
     * 判断是否比指定层级更低
     *
     * @param other 其他层级
     * @return 如果更低返回true
     */
    public boolean isLowerThan(CacheTier other) {
        return this.level > other.level;
    }

    @Override
    public String toString() {
        return name + "(" + description + ")";
    }
}