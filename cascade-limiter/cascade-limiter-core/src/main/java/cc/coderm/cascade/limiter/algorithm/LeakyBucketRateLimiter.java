package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterClockRollbackTracker;
import cc.coderm.cascade.limiter.support.RedisLuaScriptExecutor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 漏桶限流算法
 *
 * <p>实现思路：在 Redis 中维护两个字段（Hash）：
 * <ul>
 *   <li>water    - 当前桶内水量（待处理请求数）</li>
 *   <li>lastLeak - 上次漏水时间戳（毫秒）</li>
 * </ul>
 * 每次请求时，先按时间差计算已漏出的水量，再判断桶是否装得下新请求。
 *
 * <p>优点：严格匀速输出（leakRate req/s），有效保护下游服务。
 * <p>缺点：无法消化突发流量，超出 capacity 的请求直接拒绝（非排队）。
 *         若需排队语义，应在应用层使用队列 + 定时器配合。
 *
 * <p>配置说明：
 * <ul>
 *   <li>maxPermits = 桶的最大容量（并发排队上限）</li>
 *   <li>leakRate   = 每秒漏出（处理）的请求数</li>
 * </ul>
 */
@Slf4j
public class LeakyBucketRateLimiter extends AbstractRateLimiter {
    /**
     * Lua 脚本：
     * 1. 读取当前 water 和 lastLeak
     * 2. 根据时间差计算漏水量，更新 water
     * 3. 若 water + permits <= capacity 则放行，否则拒绝
     */
    private static final String LEAKY_BUCKET_LUA = """
            local key      = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local leakRate = tonumber(ARGV[2])
            local permits  = tonumber(ARGV[3])
            local ttl      = tonumber(ARGV[4])
            
            local redisTime = redis.call('TIME')
            local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
            
            local rollback = 0
            local previousNow = tonumber(redis.call('HGET', key, '__last_now') or tostring(now))
            if now < previousNow then
                rollback = 1
                now = previousNow
            end
            
            local water    = tonumber(redis.call('HGET', key, 'water') or '0')
            local lastLeak = tonumber(redis.call('HGET', key, 'lastLeak') or tostring(now))
            
            local elapsedMs = math.max(0, now - lastLeak)
            local leaked    = math.floor(elapsedMs * leakRate / 1000)
            if leaked > 0 then
                water = math.max(0, water - leaked)
                -- 只推进“已完成漏出”的时间，保留不足 1 token 的时间余量，避免系统性漂移
                lastLeak = lastLeak + math.floor(leaked * 1000 / leakRate)
            end
            
            if water + permits <= capacity then
                water = water + permits
                redis.call('HMSET', key, 'water', water, 'lastLeak', lastLeak, '__last_now', now)
                redis.call('PEXPIRE', key, ttl)
                return {1, capacity - water, rollback, previousNow, now}
            else
                redis.call('HMSET', key, 'water', water, 'lastLeak', lastLeak, '__last_now', now)
                redis.call('PEXPIRE', key, ttl)
                local overflow = water + permits - capacity
                local waitMs = math.ceil(overflow / leakRate * 1000)
                return {0, waitMs, rollback, previousNow, now}
            end
            """;

    private static final long MAX_STATE_TTL_MS = TimeUnit.DAYS.toMillis(30);

    private final RedisLuaScriptExecutor scriptExecutor;
    @Nullable
    private final LimiterClockRollbackTracker clockRollbackTracker;

    public LeakyBucketRateLimiter(RedisLuaScriptExecutor scriptExecutor,
                                  @Nullable LimiterClockRollbackTracker clockRollbackTracker) {
        this.scriptExecutor = scriptExecutor;
        this.clockRollbackTracker = clockRollbackTracker;
    }


    @Override
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
        long capacity = strategy.getMaxPermits();
        long leakRate = strategy.effectiveLeakRate();
        validateParams(capacity, leakRate, permits);
        long ttlMs = calculateStateTtlMs(capacity, leakRate);

        List<Long> result = scriptExecutor.evalLongList(
                RScript.Mode.READ_WRITE,
                LEAKY_BUCKET_LUA,
                List.of(key),
                capacity, leakRate, (long) permits, ttlMs
        );

        maybeTrackClockRollback(key, result);

        boolean allowed = result.get(0) == 1L;
        long extra = result.get(1);

        if (allowed) {
            return RateLimitResult.allowed(key, AlgorithmType.LEAKY_BUCKET, extra);
        }
        return RateLimitResult.rejected(key, AlgorithmType.LEAKY_BUCKET, extra);
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        return AlgorithmType.LEAKY_BUCKET;
    }

    /**
     * 校验漏桶算法参数合法性
     * <p>
     * 确保桶容量、漏出速率和请求 permits 均为正值，
     * 任何参数不合法都会抛出 IllegalArgumentException。
     *
     * @param capacity 桶的最大容量，必须大于 0
     * @param leakRate 每秒漏出的请求数，必须大于 0
     * @param permits  请求的 permits 数量，必须大于 0
     * @throws IllegalArgumentException 当任一参数小于等于 0 时抛出
     */
    private static void validateParams(long capacity, long leakRate, int permits) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Leaky bucket capacity (maxPermits) must be > 0");
        }
        if (leakRate <= 0) {
            throw new IllegalArgumentException("Leaky bucket leakRate must be > 0");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("Requested permits must be > 0");
        }
    }

    /**
     * 计算限流状态的 TTL（过期时间）
     * <p>
     * 根据桶容量和漏出速率计算状态字段的过期时间，策略为：
     * TTL = 桶满后完全漏空时间的 2 倍，兼顾状态复用与自动回收。
     * 同时设置硬上限（30天）防止极端配置导致 Redis key 长驻。
     *
     * @param capacity 桶的最大容量（最大 permits 数）
     * @param leakRate 每秒漏出的请求数（处理速率）
     * @return 计算后的 TTL 值，单位为毫秒，范围 [1, MAX_STATE_TTL_MS]
     */
    static long calculateStateTtlMs(long capacity, long leakRate) {
        // TTL = 桶满后“完全漏空时间”的 2 倍：兼顾状态复用与自动回收，并设置硬上限防止极端配置长驻
        long drainMs = Math.max(1L, (long) Math.ceil(capacity * 1000.0 / leakRate));
        long candidate = drainMs >= (MAX_STATE_TTL_MS + 1) / 2 ? MAX_STATE_TTL_MS : drainMs * 2;
        return Math.max(1L, Math.min(candidate, MAX_STATE_TTL_MS));
    }

    /**
     * 根据 Lua 脚本返回结果判断是否需要记录时钟回拨事件
     * <p>
     * 从 Lua 脚本返回的 result 列表中检查时钟回拨标志（索引 2），
     * 如果检测到时钟回拨，则调用 tracker 记录该事件，包含回拨前后的时间戳。
     *
     * @param key    限流 key，用于标识具体的限流实例
     * @param result Lua 脚本返回的结果列表，结构为：
     *               [0]=是否允许, [1]=额外信息, [2]=时钟回拨标志, [3]=回拨前时间戳, [4]=回拨后时间戳
     */
    private void maybeTrackClockRollback(String key, List<Long> result) {
        // 检查是否启用时钟回拨追踪以及结果列表是否完整
        if (clockRollbackTracker == null || result.size() < 5) {
            return;
        }
        // 检测到时钟回拨，记录事件
        if (result.get(2) == 1L) {
            clockRollbackTracker.recordClockRollback(
                    key,
                    AlgorithmType.LEAKY_BUCKET,
                    result.get(3),
                    result.get(4)
            );
        }
    }
}
