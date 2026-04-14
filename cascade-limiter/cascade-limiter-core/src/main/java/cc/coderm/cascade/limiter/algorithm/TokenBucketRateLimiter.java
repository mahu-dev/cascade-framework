package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.RateLimiterMathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 令牌桶限流算法（Redisson RRateLimiter）
 *
 * <p>实现思路：直接委托给 Redisson 的 RRateLimiter，后者使用 Lua 脚本在 Redis 侧
 * 维护令牌桶状态（令牌数量 + 上次补充时间）。
 *
 * <p>优点：允许短时突发流量（桶满时可一次消费多令牌），长期速率受 refillRate 约束；
 * Redisson 原生支持，集群安全，实现简洁。
 * <p>缺点：需要额外的 Redis Hash key 存储状态；
 * 跨节点时钟偏移可能引入微小误差（Redisson 已处理）。
 *
 * <p>配置说明：
 * <ul>
 *   <li>maxPermits  = 令牌桶容量（最大突发量）</li>
 *   <li>refillRate  = 每秒补充令牌数，默认等于 maxPermits</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class TokenBucketRateLimiter extends AbstractRateLimiter {

    private static final RateType TOKEN_BUCKET_RATE_TYPE = RateType.OVERALL;
    private static final long RATE_INTERVAL_SECONDS = 1L;
    private static final long RATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(RATE_INTERVAL_SECONDS);

    private final RedissonClient redissonClient;
    private final ConcurrentMap<String, CachedRateConfig> rateConfigCache = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
        RRateLimiter limiter = getOrCreateLimiter(key, strategy);
        boolean allowed = limiter.tryAcquire(permits);
        if (allowed) {
            return RateLimitResult.allowed(key, AlgorithmType.TOKEN_BUCKET,
                    limiter.availablePermits());
        }
        long waitMs = estimateWaitMillis(limiter, permits, strategy.effectiveRefillRate());
        return RateLimitResult.rejected(key, AlgorithmType.TOKEN_BUCKET, waitMs);
    }

    @Override
    public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) throws InterruptedException {
        RRateLimiter limiter = getOrCreateLimiter(key, strategy);
        if (timeoutMs <= 0) {
            limiter.acquire(1);
            return true;
        }
        return limiter.tryAcquire(1, timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        return AlgorithmType.TOKEN_BUCKET;
    }

    /**
     * 获取或创建 RRateLimiter，并确保速率配置与策略一致。
     *
     * <p>策略：
     * <ol>
     *   <li>命中本地缓存且配置一致：直接复用（零额外 Redis 配置开销）</li>
     *   <li>缓存未命中：读取 Redis 配置回填到本地缓存</li>
     *   <li>Redis 无配置：首次 trySetRate 初始化</li>
     *   <li>Redis 有配置但与当前策略不一致：setRate 强制刷新，避免策略漂移</li>
     * </ol>
     */
    private RRateLimiter getOrCreateLimiter(String key, RateLimitStrategy strategy) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        CachedRateConfig desired = CachedRateConfig.fromStrategy(strategy);
        CachedRateConfig cached = rateConfigCache.get(key);
        if (cached != null && cached.sameAs(desired)) {
            return limiter;
        }

        rateConfigCache.compute(key, (k, current) -> ensureLimiterRate(limiter, current, desired));
        return limiter;
    }

    /**
     * 确保 Redisson RateLimiter 的速率配置与期望策略一致
     * <p>
     * 此方法作为 {@link java.util.Map#compute} 的回调函数，由 {@link #getOrCreateLimiter}
     * 在需要更新本地缓存时调用。它通过对比本地缓存、Redis 远程配置和期望配置，
     * 决定是否需要初始化或更新 Redisson RateLimiter 的速率设置。
     * <p>
     * <b>执行流程：</b>
     * <ol>
     *   <li><b>本地缓存命中</b>：如果当前缓存配置与期望一致，直接返回缓存值（无需 Redis 操作）</li>
     *   <li><b>Redis 无配置</b>：首次访问，使用 {@code trySetRate} 初始化（并发安全）</li>
     *   <li><b>并发初始化冲突</b>：如果 {@code trySetRate} 失败（其他节点已初始化），回读并按需纠正</li>
     *   <li><b>Redis 配置一致</b>：远程配置与期望一致，直接使用远程配置</li>
     *   <li><b>强制刷新配置</b>：远程配置与期望不一致，使用 {@code setRate} 强制更新</li>
     * </ol>
     * <p>
     * <b>并发安全保证：</b>
     * <ul>
     *   <li>{@code trySetRate} 是原子操作，多个节点同时初始化时只有一个成功</li>
     *   <li>初始化失败后会回读 Redis 配置，避免重复设置</li>
     *   <li>{@code setRate} 会清除已有状态并重新初始化，确保配置立即生效</li>
     * </ul>
     *
     * @param limiter  Redisson RateLimiter 实例，需要配置速率参数
     * @param current 当前缓存的配置，可能为 null（首次访问或缓存未命中）
     * @param desired 期望的速率配置，由策略参数计算得出，保证不为 null
     * @return 应该缓存的配置对象，用于更新本地缓存
     * @throws NullPointerException 如果 desired 参数为 null（防御性检查）
     */
    private static CachedRateConfig ensureLimiterRate(RRateLimiter limiter,
                                                      CachedRateConfig current,
                                                      CachedRateConfig desired) {
        // 防御性检查：desired 由 fromStrategy() 创建，理论不为 null
        Objects.requireNonNull(desired, "CachedRateConfig.desired cannot be null");

        // 场景 1：本地缓存命中且配置一致，直接复用
        if (current != null && current.sameAs(desired)) {
            return current;
        }

        // 读取 Redis 远程配置，判断是否需要初始化或更新
        CachedRateConfig remote = readRemoteRateConfig(limiter);

        // 场景 2：Redis 无配置，首次初始化
        if (remote == null) {
            boolean initialized = limiter.trySetRate(
                    TOKEN_BUCKET_RATE_TYPE,
                    desired.rate(),
                    RATE_INTERVAL_SECONDS,
                    RateIntervalUnit.SECONDS
            );
            if (!initialized) {
                // 场景 3：并发场景下其他节点已初始化，回读并按需纠正
                remote = readRemoteRateConfig(limiter);
                if (remote == null || !remote.sameAs(desired)) {
                    limiter.setRate(
                            TOKEN_BUCKET_RATE_TYPE,
                            desired.rate(),
                            RATE_INTERVAL_SECONDS,
                            RateIntervalUnit.SECONDS
                    );
                }
            }
            return desired;
        }

        // 场景 4：Redis 配置与期望一致，直接使用远程配置
        if (remote.sameAs(desired)) {
            return remote;
        }

        // 场景 5：Redis 配置与期望不一致，强制刷新配置
        limiter.setRate(
                TOKEN_BUCKET_RATE_TYPE,
                desired.rate(),
                RATE_INTERVAL_SECONDS,
                RateIntervalUnit.SECONDS
        );
        return desired;
    }

    /**
     * 从 Redisson RateLimiter 读取远程配置
     * <p>
     * 安全地读取 Redis 中存储的速率配置，并检查配置的完整性。
     * 由于 Redisson 的 {@code getConfig()} 可能返回不完整的配置（某些字段为 null），
     * 此方法会对所有关键字段进行 null 检查，确保只有完整配置才被接受。
     * <p>
     * <b>返回 null 的场景：</b>
     * <ul>
     *   <li>RateLimiter 尚未初始化（Redis 中无配置记录）</li>
     *   <li>配置对象本身为 null（Redisson 内部状态异常）</li>
     *   <li>配置中任一关键字段为 null（rateType、rateInterval、rate）</li>
     * </ul>
     * <p>
     * <b>设计考量：</b>
     * <ul>
     *   <li>防御性编程：避免后续代码使用不完整配置导致 {@code NullPointerException}</li>
     *   <li>语义清晰：null 明确表示"配置不存在或无效"，而非部分有效</li>
     *   <li>调用方友好：调用者只需判断 null，无需逐个检查字段</li>
     * </ul>
     *
     * @param limiter Redisson RateLimiter 实例，从其读取配置
     * @return 完整的配置对象，如果配置不存在或不完整则返回 {@code null}
     */
    private static CachedRateConfig readRemoteRateConfig(RRateLimiter limiter) {
        RateLimiterConfig config = limiter.getConfig();
        // 防御性检查：确保所有关键字段都存在，任何字段为 null 都视为配置无效
        if (config == null
                || config.getRateType() == null
                || config.getRateInterval() == null
                || config.getRate() == null) {
            return null;
        }
        return new CachedRateConfig(
                config.getRateType(),
                config.getRateInterval(),
                config.getRate()
        );
    }

    /**
     * 估算获取指定数量令牌需要等待的时间（毫秒）
     * <p>
     * 基于令牌桶算法的核心公式计算等待时间：
     * <pre>
     * 等待时间 = 令牌缺口 * 1000 / 补充速率
     * </pre>
     * 其中：
     * <ul>
     *   <li>令牌缺口 = 请求令牌数 - 当前可用令牌数（如果大于 0）</li>
     *   <li>补充速率 = 每秒补充的令牌数</li>
     *   <li>乘以 1000 是将秒转换为毫秒</li>
     * </ul>
     * <p>
     * <b>使用场景：</b>当限流拒绝请求时，通过此方法估算客户端需要等待多久
     * 才能重试请求，返回给客户端作为参考值（如 HTTP 429 响应的 {@code Retry-After} 头）。
     * <p>
     * <b>边界情况处理：</b>
     * <ul>
     *   <li>请求令牌数 ≤ 0：视为无效请求，无需等待</li>
     *   <li>补充速率 ≤ 0：桶已停止补充，永远无法获取令牌，返回 0（避免除零错误）</li>
     *   <li>当前令牌充足：无需等待，立即返回 0</li>
     *   <li>读取可用令牌失败：保守估算为无令牌可用（由 {@link #readAvailablePermitsSafely} 处理）</li>
     * </ul>
     * <p>
     * <b>注意：</b>此返回值仅为估算值，实际等待时间可能因并发请求、时钟漂移等因素有所偏差。
     *
     * @param limiter    Redisson RateLimiter 实例，用于查询当前可用令牌数
     * @param permits    请求的令牌数量，必须 > 0
     * @param refillRate 每秒补充的令牌数，必须 > 0
     * @return 需要等待的毫秒数，如果当前有足够令牌或参数无效则返回 0
     */
    private static long estimateWaitMillis(RRateLimiter limiter, int permits, long refillRate) {
        // 边界检查：无效参数无需等待
        if (permits <= 0 || refillRate <= 0) {
            return 0L;
        }

        // 读取当前可用令牌数（失败时保守返回 0）
        long availablePermits = readAvailablePermitsSafely(limiter);

        // 计算令牌缺口，最小为 0（当前令牌足够时无需等待）
        long deficit = Math.max(0L, (long) permits - availablePermits);
        if (deficit == 0L) {
            return 0L;
        }

        // 核心公式：等待时间 = (缺口 * 1000) / 补充速率，向上取整
        return RateLimiterMathUtils.ceilDiv(deficit * 1000L, refillRate);
    }

    /**
     * 安全地读取 Redisson RateLimiter 的可用令牌数
     * <p>
     * 调用 Redisson 的 {@code availablePermits()} 方法查询当前可用令牌数，
     * 并捕获所有可能的运行时异常，确保即使 Redis 连接异常或 Redisson 内部错误
     * 也不会导致调用方崩溃。
     * <p>
     * <b>异常来源：</b>
     * <ul>
     *   <li>Redis 连接断开或超时</li>
     *   <li>Redisson 客户端内部状态异常</li>
     *   <li>Redis 服务端返回错误响应</li>
     *   <li>RateLimiter 尚未初始化（某些版本的 Redisson 可能抛异常）</li>
     * </ul>
     * <p>
     * <b>保守策略：</b>
     * 当任何异常发生时，返回 {@code 0}（无可用令牌），这是一种保守的估算：
     * <ul>
     *   <li>避免向客户端承诺过多令牌（防止限流失效）</li>
     *   <li>在不确定时假设最坏情况，保证系统稳定性</li>
     *   <li>客户端会收到更长的等待时间建议，但不会导致系统过载</li>
     * </ul>
     * <p>
     * <b>数学保证：</b>即使 {@code availablePermits()} 返回负数（理论上不应该），
     * 也会通过 {@code Math.max(0L, ...)} 确保返回值 ≥ 0。
     *
     * @param limiter Redisson RateLimiter 实例
     * @return 可用令牌数，保证 ≥ 0；异常时返回 0（保守估算）
     */
    private static long readAvailablePermitsSafely(RRateLimiter limiter) {
        try {
            return Math.max(0L, limiter.availablePermits());
        } catch (RuntimeException ex) {
            // 回退到保守估算：视为当前桶内无可用令牌
            return 0L;
        }
    }

    /**
     * 速率配置缓存对象
     * <p>
     * 用于缓存 Redisson RateLimiter 的速率配置，避免频繁读取 Redis。
     * 封装了速率配置的三个核心参数：速率类型、时间间隔和速率值。
     * <p>
     * <b>字段说明：</b>
     * <ul>
     *   <li><b>rateType</b>: 速率类型（OVERALL 全局 / PER_CLIENT 单客户端）</li>
     *   <li><b>rateIntervalMs</b>: 速率间隔时长，单位为毫秒</li>
     *   <li><b>rate</b>: 速率值，表示在每个间隔内允许的请求数或令牌数</li>
     * </ul>
     * <p>
     * <b>使用场景：</b>作为本地缓存的值，记录已知的速率配置。
     * 通过 {@link #sameAs} 方法判断配置是否相同，决定是否需要更新 Redis 配置。
     *
     * @param rateType       速率类型
     * @param rateIntervalMs 速率间隔时长，单位为毫秒
     * @param rate           速率值
     */
    private record CachedRateConfig(RateType rateType, long rateIntervalMs, long rate) {
        private static CachedRateConfig fromStrategy(RateLimitStrategy strategy) {
            return new CachedRateConfig(
                    TOKEN_BUCKET_RATE_TYPE,
                    RATE_INTERVAL_MS,
                    strategy.effectiveRefillRate()
            );
        }

        /**
         * 判断此配置与另一个配置是否在所有关键字段上相等
         * <p>
         * 比较速率配置的所有核心参数，用于判断是否需要更新 Redisson RateLimiter 的配置。
         * 只有当所有字段都完全匹配时，才认为配置相同，避免因字段差异导致限流行为不一致。
         * <p>
         * <b>比较的字段：</b>
         * <ol>
         *   <li>{@code rateType}：速率类型（OVERALL/PER_CLIENT），影响限流的作用范围</li>
         *   <li>{@code rateIntervalMs}：速率间隔时长，影响令牌补充的时间窗口</li>
         *   <li>{@code rate}：速率值（每秒补充的令牌数），影响限流的严格程度</li>
         * </ol>
         * <p>
         * <b>null 安全：</b>如果 {@code other} 参数为 {@code null}，返回 {@code false}，
         * 避免在并发场景或其他节点刚初始化时出现 NullPointerException。
         * <p>
         * <b>使用场景：</b>在 {@link #ensureLimiterRate} 方法中，用于对比本地缓存、
         * Redis 远程配置和期望配置，决定是否需要调用 {@code setRate} 更新配置。
         *
         * @param other 要比较的另一个配置对象，可能为 {@code null}
         * @return 如果所有关键字段都相等则返回 {@code true}，否则返回 {@code false}
         */
        private boolean sameAs(CachedRateConfig other) {
            return other != null
                    && rate == other.rate
                    && rateIntervalMs == other.rateIntervalMs
                    && rateType == other.rateType;
        }
    }
}
