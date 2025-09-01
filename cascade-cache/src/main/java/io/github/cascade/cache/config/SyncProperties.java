package io.github.cascade.cache.config;

import lombok.Data;
import lombok.Getter;

/**
 * 缓存同步配置属性
 * <p>
 * 设计原则：
 * 1. 流式配置：支持Builder模式的链式调用
 * 2. 类型安全：使用枚举定义同步类型
 * 3. 默认优化：提供合理的默认配置
 * 4. 可扩展性：为未来的同步方式预留扩展空间
 *
 * @author cascade
 */
@Data
public class SyncProperties {

    /**
     * 是否启用缓存同步
     */
    private boolean enabled = true;

    /**
     * 同步类型
     */
    private SyncType type = SyncType.REDIS;

    /**
     * Redis主题前缀
     */
    private String topicPrefix = "cascade:cache:sync:";

    /**
     * 是否异步发布事件
     */
    private boolean asyncPublish = true;

    /**
     * 同步超时时间(毫秒)
     */
    private long timeoutMs = 5000L;

    /**
     * 批处理大小
     */
    private int batchSize = 100;

    /**
     * 同步类型枚举
     */
    @Getter
    public enum SyncType {
        /**
         * Redis发布订阅
         */
        REDIS("redis"),

        /**
         * 本地内存(无跨节点同步)
         */
        LOCAL("local"),

        /**
         * 自定义实现
         */
        CUSTOM("custom");

        private final String value;

        SyncType(String value) {
            this.value = value;
        }

        /**
         * 从字符串值获取枚举
         */
        public static SyncType fromValue(String value) {
            return java.util.stream.Stream.of(values())
                    .filter(type -> type.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(REDIS);
        }
    }

    // ==================== 流式配置方法 ====================

    /**
     * 启用同步
     */
    public SyncProperties enable() {
        this.enabled = true;
        return this;
    }

    /**
     * 禁用同步
     */
    public SyncProperties disable() {
        this.enabled = false;
        return this;
    }

    /**
     * 设置同步类型
     */
    public SyncProperties type(SyncType type) {
        this.type = type;
        return this;
    }

    /**
     * 设置Redis主题前缀
     */
    public SyncProperties topicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
        return this;
    }

    /**
     * 启用异步发布
     */
    public SyncProperties asyncPublish() {
        this.asyncPublish = true;
        return this;
    }

    /**
     * 禁用异步发布
     */
    public SyncProperties syncPublish() {
        this.asyncPublish = false;
        return this;
    }

    /**
     * 设置超时时间
     */
    public SyncProperties timeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    /**
     * 设置批处理大小
     */
    public SyncProperties batchSize(int batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建Redis同步配置
     */
    public static SyncProperties redis(String topicPrefix) {
        return new SyncProperties()
                .enable()
                .type(SyncType.REDIS)
                .topicPrefix(topicPrefix)
                .asyncPublish();
    }

    /**
     * 创建本地同步配置
     */
    public static SyncProperties local() {
        return new SyncProperties()
                .enable()
                .type(SyncType.LOCAL);
    }

    /**
     * 创建禁用同步配置
     */
    public static SyncProperties disabled() {
        return new SyncProperties().disable();
    }

    /**
     * 验证配置有效性
     */
    public boolean isValid() {
        if (!enabled) {
            return true;
        }

        return switch (type) {
            case REDIS -> topicPrefix != null && !topicPrefix.trim().isEmpty();
            case LOCAL -> true;
            case CUSTOM -> true;
        };
    }

    @Override
    public String toString() {
        return String.format("SyncProperties{enabled=%s, type=%s, topicPrefix='%s', async=%s}",
                enabled, type, topicPrefix, asyncPublish);
    }
}