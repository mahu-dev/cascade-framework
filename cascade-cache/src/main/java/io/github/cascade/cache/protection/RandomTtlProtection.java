package io.github.cascade.cache.protection;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 随机TTL防雪崩保护
 * 通过为缓存键添加随机的过期时间来防止缓存雪崩
 *
 * @author Cascade Framework
 */
public class RandomTtlProtection {

    private final Duration baseTtl;
    private final Duration jitterRange;
    private final JitterStrategy jitterStrategy;
    private final Function<Object, Duration> customJitterFunction;
    private final Random random;

    /**
     * 构造函数
     *
     * @param baseTtl 基础TTL
     * @param jitterRange 抖动范围
     * @param jitterStrategy 抖动策略
     */
    public RandomTtlProtection(Duration baseTtl, Duration jitterRange, JitterStrategy jitterStrategy) {
        this(baseTtl, jitterRange, jitterStrategy, null);
    }

    /**
     * 构造函数（支持自定义抖动函数）
     *
     * @param baseTtl 基础TTL
     * @param jitterRange 抖动范围
     * @param jitterStrategy 抖动策略
     * @param customJitterFunction 自定义抖动函数
     */
    public RandomTtlProtection(Duration baseTtl, Duration jitterRange, 
                              JitterStrategy jitterStrategy, 
                              Function<Object, Duration> customJitterFunction) {
        this.baseTtl = baseTtl;
        this.jitterRange = jitterRange;
        this.jitterStrategy = jitterStrategy;
        this.customJitterFunction = customJitterFunction;
        this.random = new Random();
    }

    /**
     * 计算带抖动的TTL
     *
     * @param key 缓存键
     * @return 带抖动的TTL
     */
    public Duration calculateTtl(Object key) {
        if (customJitterFunction != null) {
            Duration customJitter = customJitterFunction.apply(key);
            return baseTtl.plus(customJitter);
        }

        Duration jitter = calculateJitter(key);
        return baseTtl.plus(jitter);
    }

    /**
     * 计算抖动值
     *
     * @param key 缓存键
     * @return 抖动值
     */
    private Duration calculateJitter(Object key) {
        switch (jitterStrategy) {
            case UNIFORM:
                return calculateUniformJitter();
            case GAUSSIAN:
                return calculateGaussianJitter();
            case EXPONENTIAL:
                return calculateExponentialJitter();
            case HASH_BASED:
                return calculateHashBasedJitter(key);
            case PERCENTAGE:
                return calculatePercentageJitter();
            default:
                return Duration.ZERO;
        }
    }

    /**
     * 均匀分布抖动
     */
    private Duration calculateUniformJitter() {
        long jitterMillis = ThreadLocalRandom.current().nextLong(
            -jitterRange.toMillis(), jitterRange.toMillis() + 1);
        return Duration.ofMillis(jitterMillis);
    }

    /**
     * 高斯分布抖动
     */
    private Duration calculateGaussianJitter() {
        double gaussian = ThreadLocalRandom.current().nextGaussian();
        // 将高斯分布限制在[-3σ, 3σ]范围内
        gaussian = Math.max(-3.0, Math.min(3.0, gaussian));
        long jitterMillis = (long) (gaussian * jitterRange.toMillis() / 3.0);
        return Duration.ofMillis(jitterMillis);
    }

    /**
     * 指数分布抖动
     */
    private Duration calculateExponentialJitter() {
        double exponential = -Math.log(1.0 - ThreadLocalRandom.current().nextDouble());
        // 标准化到抖动范围
        long jitterMillis = (long) (exponential * jitterRange.toMillis() / 2.0);
        // 随机选择正负
        if (ThreadLocalRandom.current().nextBoolean()) {
            jitterMillis = -jitterMillis;
        }
        return Duration.ofMillis(jitterMillis);
    }

    /**
     * 基于哈希的确定性抖动
     */
    private Duration calculateHashBasedJitter(Object key) {
        if (key == null) {
            return Duration.ZERO;
        }
        
        int hash = key.hashCode();
        // 使用哈希值生成确定性的抖动
        long jitterMillis = (hash % (2 * jitterRange.toMillis() + 1)) - jitterRange.toMillis();
        return Duration.ofMillis(jitterMillis);
    }

    /**
     * 百分比抖动
     */
    private Duration calculatePercentageJitter() {
        // jitterRange作为百分比使用
        double percentage = ThreadLocalRandom.current().nextDouble(
            -jitterRange.toMillis() / 100.0, jitterRange.toMillis() / 100.0 + 0.01);
        long jitterMillis = (long) (baseTtl.toMillis() * percentage);
        return Duration.ofMillis(jitterMillis);
    }

    /**
     * 获取基础TTL
     */
    public Duration getBaseTtl() {
        return baseTtl;
    }

    /**
     * 获取抖动范围
     */
    public Duration getJitterRange() {
        return jitterRange;
    }

    /**
     * 获取抖动策略
     */
    public JitterStrategy getJitterStrategy() {
        return jitterStrategy;
    }

    /**
     * 抖动策略枚举
     */
    public enum JitterStrategy {
        /** 均匀分布：在[-jitterRange, +jitterRange]范围内均匀分布 */
        UNIFORM,
        
        /** 高斯分布：以0为中心的正态分布，3σ = jitterRange */
        GAUSSIAN,
        
        /** 指数分布：指数分布的抖动 */
        EXPONENTIAL,
        
        /** 基于哈希：使用键的哈希值生成确定性抖动 */
        HASH_BASED,
        
        /** 百分比：jitterRange作为baseTtl的百分比 */
        PERCENTAGE
    }

    /**
     * 创建随机TTL保护构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 构建器
     */
    public static class Builder {
        private Duration baseTtl = Duration.ofMinutes(30);
        private Duration jitterRange = Duration.ofMinutes(5);
        private JitterStrategy jitterStrategy = JitterStrategy.UNIFORM;
        private Function<Object, Duration> customJitterFunction;

        public Builder baseTtl(Duration baseTtl) {
            this.baseTtl = baseTtl;
            return this;
        }

        public Builder jitterRange(Duration jitterRange) {
            this.jitterRange = jitterRange;
            return this;
        }

        public Builder jitterStrategy(JitterStrategy jitterStrategy) {
            this.jitterStrategy = jitterStrategy;
            return this;
        }

        public Builder customJitterFunction(Function<Object, Duration> customJitterFunction) {
            this.customJitterFunction = customJitterFunction;
            return this;
        }

        /**
         * 设置均匀分布抖动
         */
        public Builder uniformJitter(Duration jitterRange) {
            this.jitterRange = jitterRange;
            this.jitterStrategy = JitterStrategy.UNIFORM;
            return this;
        }

        /**
         * 设置高斯分布抖动
         */
        public Builder gaussianJitter(Duration jitterRange) {
            this.jitterRange = jitterRange;
            this.jitterStrategy = JitterStrategy.GAUSSIAN;
            return this;
        }

        /**
         * 设置指数分布抖动
         */
        public Builder exponentialJitter(Duration jitterRange) {
            this.jitterRange = jitterRange;
            this.jitterStrategy = JitterStrategy.EXPONENTIAL;
            return this;
        }

        /**
         * 设置基于哈希的确定性抖动
         */
        public Builder hashBasedJitter(Duration jitterRange) {
            this.jitterRange = jitterRange;
            this.jitterStrategy = JitterStrategy.HASH_BASED;
            return this;
        }

        /**
         * 设置百分比抖动
         */
        public Builder percentageJitter(double percentage) {
            this.jitterRange = Duration.ofMillis((long) (percentage * 100));
            this.jitterStrategy = JitterStrategy.PERCENTAGE;
            return this;
        }

        /**
         * 设置自定义抖动函数
         */
        public Builder customJitter(Function<Object, Duration> jitterFunction) {
            this.customJitterFunction = jitterFunction;
            return this;
        }

        public RandomTtlProtection build() {
            return new RandomTtlProtection(baseTtl, jitterRange, jitterStrategy, customJitterFunction);
        }
    }

    /**
     * 预定义的常用配置
     */
    public static class Presets {
        
        /**
         * 轻度抖动：±10%的TTL抖动
         */
        public static RandomTtlProtection lightJitter(Duration baseTtl) {
            return builder()
                .baseTtl(baseTtl)
                .percentageJitter(0.1)
                .build();
        }

        /**
         * 中度抖动：±20%的TTL抖动
         */
        public static RandomTtlProtection mediumJitter(Duration baseTtl) {
            return builder()
                .baseTtl(baseTtl)
                .percentageJitter(0.2)
                .build();
        }

        /**
         * 重度抖动：±30%的TTL抖动
         */
        public static RandomTtlProtection heavyJitter(Duration baseTtl) {
            return builder()
                .baseTtl(baseTtl)
                .percentageJitter(0.3)
                .build();
        }

        /**
         * 固定范围抖动
         */
        public static RandomTtlProtection fixedRangeJitter(Duration baseTtl, Duration jitterRange) {
            return builder()
                .baseTtl(baseTtl)
                .uniformJitter(jitterRange)
                .build();
        }

        /**
         * 高斯分布抖动
         */
        public static RandomTtlProtection gaussianJitter(Duration baseTtl, Duration jitterRange) {
            return builder()
                .baseTtl(baseTtl)
                .gaussianJitter(jitterRange)
                .build();
        }

        /**
         * 确定性抖动（基于键的哈希）
         */
        public static RandomTtlProtection deterministicJitter(Duration baseTtl, Duration jitterRange) {
            return builder()
                .baseTtl(baseTtl)
                .hashBasedJitter(jitterRange)
                .build();
        }
    }
}