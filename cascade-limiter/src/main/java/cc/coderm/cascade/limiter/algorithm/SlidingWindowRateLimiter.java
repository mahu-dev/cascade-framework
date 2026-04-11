package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterClockRollbackTracker;
import cc.coderm.cascade.limiter.support.RateLimiterMathUtils;
import cc.coderm.cascade.limiter.support.RedisLuaScriptExecutor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 滑动窗口限流算法（分桶计数版）
 *
 * <p>实现思路：将窗口按固定桶宽切分，在 Redis Hash 中维护“桶计数 + total”。
 * 每次请求仅在固定桶数量范围内做过期清理与计数更新，避免每请求一条 ZSet 记录。
 *
 * <p>优点：内存与计算复杂度和“桶数”相关，而不是和“窗口内请求数”相关，
 * 高并发下更稳定。
 * <p>代价：相对逐请求记录的精确滑动窗口，引入桶宽级别的近似误差。
 */
@Slf4j
public class SlidingWindowRateLimiter extends AbstractRateLimiter {

    private static final long TARGET_BUCKET_MS = 100L;
    private static final int MIN_BUCKET_SLOTS = 10;
    private static final int MAX_BUCKET_SLOTS = 200;
    private static final long MAX_STATE_TTL_MS = TimeUnit.DAYS.toMillis(30);

    /**
     * Lua 脚本：
     * 1. 按窗口参数初始化/刷新状态结构
     * 2. 清理过期桶并修正 total
     * 3. 判断 total + permits 是否超限
     * 4. 未超限则写入当前桶计数并更新 total
     */
    private static final String SLIDING_WINDOW_LUA = """
            local key      = KEYS[1]
            local window   = tonumber(ARGV[1])
            local limit    = tonumber(ARGV[2])
            local permits  = tonumber(ARGV[3])
            local bucket   = tonumber(ARGV[4])
            local slots    = tonumber(ARGV[5])
            local ttl      = tonumber(ARGV[6])
            
            local redisTime = redis.call('TIME')
            local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
            
            local confWindow = tonumber(redis.call('HGET', key, '__window') or '-1')
            local confBucket = tonumber(redis.call('HGET', key, '__bucket') or '-1')
            local confSlots  = tonumber(redis.call('HGET', key, '__slots') or '-1')
            if confWindow ~= window or confBucket ~= bucket or confSlots ~= slots then
                redis.call('DEL', key)
            end
            
            local rollback = 0
            local previousNow = tonumber(redis.call('HGET', key, '__last_now') or tostring(now))
            if now < previousNow then
                rollback = 1
                now = previousNow
            end
            
            local total = tonumber(redis.call('HGET', key, '__total') or '0')
            local currentBucket = math.floor(now / bucket) * bucket
            local currentIdx = math.floor(currentBucket / bucket) % slots
            local currentSlotCount = 0
            
            local fields = {}
            for i = 0, slots - 1 do
                fields[#fields + 1] = 't:' .. i
                fields[#fields + 1] = 'c:' .. i
            end
            local values = {}
            if #fields > 0 then
                values = redis.call('HMGET', key, unpack(fields))
            end
            
            for i = 0, slots - 1 do
                local base = i * 2
                local ts = tonumber(values[base + 1])
                local cnt = tonumber(values[base + 2]) or 0
                if ts ~= nil then
                    if now - ts >= window then
                        if cnt > 0 then
                            total = math.max(0, total - cnt)
                        end
                        redis.call('HDEL', key, 't:' .. i, 'c:' .. i)
                    elseif i == currentIdx then
                        if ts == currentBucket then
                            currentSlotCount = cnt
                        else
                            if cnt > 0 then
                                total = math.max(0, total - cnt)
                            end
                            redis.call('HDEL', key, 't:' .. i, 'c:' .. i)
                        end
                    end
                end
            end
            
            if total + permits > limit then
                local overflow = total + permits - limit
                local waitMs = math.ceil(overflow * window / limit)
                if waitMs < 1 then
                    waitMs = 1
                end
                redis.call('HSET', key,
                    '__total', total,
                    '__window', window,
                    '__bucket', bucket,
                    '__slots', slots,
                    '__last_now', now
                )
                redis.call('PEXPIRE', key, ttl)
                return {0, waitMs, rollback, previousNow, now}
            end
            
            local newSlotCount = currentSlotCount + permits
            total = total + permits
            redis.call('HSET', key,
                't:' .. currentIdx, currentBucket,
                'c:' .. currentIdx, newSlotCount,
                '__total', total,
                '__window', window,
                '__bucket', bucket,
                '__slots', slots,
                '__last_now', now
            )
            redis.call('PEXPIRE', key, ttl)
            return {1, limit - total, rollback, previousNow, now}
            """;

    private final RedisLuaScriptExecutor scriptExecutor;
    @Nullable
    private final LimiterClockRollbackTracker clockRollbackTracker;

    public SlidingWindowRateLimiter(RedisLuaScriptExecutor scriptExecutor,
                                    @Nullable LimiterClockRollbackTracker clockRollbackTracker) {
        this.scriptExecutor = scriptExecutor;
        this.clockRollbackTracker = clockRollbackTracker;
    }


    @Override
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
        WindowConfig windowConfig = buildWindowConfig(strategy.getWindow().toMillis());
        validateParams(windowConfig.windowMs(), strategy.getMaxPermits(), permits);

        List<Long> result = scriptExecutor.evalLongList(
                RScript.Mode.READ_WRITE,
                SLIDING_WINDOW_LUA,
                Collections.singletonList(key),
                windowConfig.windowMs(),
                strategy.getMaxPermits(),
                (long) permits,
                windowConfig.bucketMs(),
                (long) windowConfig.slotCount(),
                windowConfig.ttlMs()
        );

        maybeTrackClockRollback(key, result);

        boolean allowed = result.get(0) == 1L;
        long extra = result.get(1);

        if (allowed) {
            return RateLimitResult.allowed(key, AlgorithmType.SLIDING_WINDOW, extra);
        }
        return RateLimitResult.rejected(key, AlgorithmType.SLIDING_WINDOW, extra);
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        return AlgorithmType.SLIDING_WINDOW;
    }

    /**
     * 根据窗口时长计算桶数量
     * <p>
     * 按照目标桶宽（TARGET_BUCKET_MS = 100ms）计算理想的桶数量，
     * 并将其限制在 [MIN_BUCKET_SLOTS, MAX_BUCKET_SLOTS] 范围内，
     * 避免桶数量过多或过少影响性能和精度。
     *
     * @param windowMs 窗口时长，单位为毫秒
     * @return 计算后的桶数量，范围在 [MIN_BUCKET_SLOTS, MAX_BUCKET_SLOTS] 之间
     */
    static int calculateSlotCount(long windowMs) {
        long byTarget = RateLimiterMathUtils.ceilDiv(windowMs, TARGET_BUCKET_MS);
        long clamped = Math.max(MIN_BUCKET_SLOTS, Math.min(MAX_BUCKET_SLOTS, byTarget));
        return (int) clamped;
    }

    /**
     * 计算限流状态的 TTL（过期时间）
     * <p>
     * 根据窗口时长计算状态字段的过期时间，策略为窗口时长的 2 倍，
     * 兼顾状态复用与自动回收。同时设置硬上限（30天）防止极端配置导致 Redis key 长驻。
     *
     * @param windowMs 窗口时长，单位为毫秒
     * @return 计算后的 TTL 值，单位为毫秒，范围 [1, MAX_STATE_TTL_MS]
     */
    static long calculateStateTtlMs(long windowMs) {
        long candidate = windowMs >= (MAX_STATE_TTL_MS + 1) / 2 ? MAX_STATE_TTL_MS : windowMs * 2;
        return Math.max(1L, Math.min(candidate, MAX_STATE_TTL_MS));
    }

    /**
     * 构建滑动窗口配置对象
     * <p>
     * 根据原始窗口时长计算并生成完整的窗口配置，包括：
     * <ul>
     *   <li>窗口时长：规范化为至少 1ms，避免零或负值</li>
     *   <li>槽位数量：根据窗口时长动态计算，平衡精度与性能</li>
     *   <li>槽位宽度：窗口时长除以槽位数量，向上取整确保覆盖完整窗口</li>
     *   <li>状态 TTL：设置 Redis key 的过期时间，防止内存泄漏</li>
     * </ul>
     *
     * @param rawWindowMs 原始窗口时长，单位为毫秒
     * @return 窗口配置对象，包含规范化后的窗口参数
     */
    private static WindowConfig buildWindowConfig(long rawWindowMs) {
        long windowMs = Math.max(1L, rawWindowMs);
        int slotCount = calculateSlotCount(windowMs);
        long bucketMs = Math.max(1L, RateLimiterMathUtils.ceilDiv(windowMs, slotCount));
        long ttlMs = calculateStateTtlMs(windowMs);
        return new WindowConfig(windowMs, bucketMs, slotCount, ttlMs);
    }

    /**
     * 验证限流参数的合法性
     * <p>
     * 在执行限流逻辑前，校验关键参数是否符合业务规则，避免非法参数导致：
     * <ul>
     *   <li>Redis 错误：窗口时长 ≤ 0 会导致 Redis TTL 设置失败</li>
     *   <li>逻辑错误：限制数量 ≤ 0 会使限流失效</li>
     *   <li>资源浪费：请求配额 ≤ 0 无实际意义</li>
     * </ul>
     *
     * @param windowMs 窗口时长，必须 > 0
     * @param limit    窗口内最大允许请求数，必须 > 0
     * @param permits  请求的配额数量，必须 > 0
     * @throws IllegalArgumentException 当任一参数不满足条件时抛出
     */
    private static void validateParams(long windowMs, long limit, int permits) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("Sliding window duration must be > 0 ms");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Sliding window maxPermits must be > 0");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("Requested permits must be > 0");
        }
    }


    /**
     * 可选地追踪和记录时钟回滚事件
     * <p>
     * 在分布式系统中，服务器时钟可能被手动调整或 NTP 同步导致时间倒流，
     * 这会严重影响滑动窗口算法的正确性。Lua 脚本会检测当前时间戳是否小于
     * 上次记录的时间戳，如果检测到回滚，会在返回值中标记并返回相关数据。
     * <p>
     * <b>Lua 脚本返回值格式：</b>
     * <pre>
     * result.get(0): 是否允许通过（0 = 拒绝，1 = 允许）
     * result.get(1): 剩余配额或等待时间
     * result.get(2): rollback 标志（0 = 正常，1 = 检测到时钟回滚）
     * result.get(3): previousNow - 回滚前的时间戳（上次记录的 __last_now）
     * result.get(4): now - 当前时间戳（Lua 脚本已将其修正为 previousNow）
     * </pre>
     * <p>
     * <b>时钟回滚的影响：</b>
     * <ul>
     *   <li>窗口边界计算错误：未来的时间戳被当作过去处理，导致窗口错位</li>
     *   <li>统计数据混乱：旧数据可能被错误地计入当前窗口</li>
     *   <li>限流失效：可能在短时间内允许超额请求通过</li>
     * </ul>
     * <p>
     * <b>处理策略：</b>如果启用了 {@code clockRollbackTracker}，则在检测到回滚时
     * 记录事件用于监控告警；Lua 脚本会将当前时间戳强制修正为上次记录的时间戳，
     * 避免进一步的计算错误。
     *
     * @param key     限流 key，用于标识具体的限流器实例
     * @param result  Lua 脚本执行结果列表，必须至少包含 5 个元素
     */
    private void maybeTrackClockRollback(String key, List<Long> result) {
        if (clockRollbackTracker == null || result.size() < 5) {
            return;
        }
        if (result.get(2) == 1L) {
            clockRollbackTracker.recordClockRollback(
                    key,
                    AlgorithmType.SLIDING_WINDOW,
                    result.get(3),
                    result.get(4)
            );
        }
    }

    /**
     * 滑动窗口配置对象
     * <p>
     * 不可变数据载体，封装滑动窗口算法所需的核心参数。
     * 通过 {@link #buildWindowConfig(long)} 方法构建，确保参数的合法性和一致性。
     * <p>
     * <b>字段说明：</b>
     * <ul>
     *   <li><b>windowMs</b>: 窗口总时长，限流统计的时间范围</li>
     *   <li><b>bucketMs</b>: 单个桶的时间宽度，窗口被分割为多个桶，每个桶统计固定时间段内的请求数</li>
     *   <li><b>slotCount</b>: 桶的总数量，根据窗口时长和目标桶宽动态计算，范围 [10, 200]</li>
     *   <li><b>ttlMs</b>: Redis 状态的过期时间，设置为窗口时长的 2 倍，上限 30 天</li>
     * </ul>
     * <p>
     * <b>设计权衡：</b>
     * <ul>
     *   <li>桶越多（bucketMs 越小），精度越高，但内存消耗越大</li>
     *   <li>桶越少（bucketMs 越大），精度越低，但内存消耗越小</li>
     *   <li>当前实现通过 {@link #TARGET_BUCKET_MS}（100ms）在精度和性能间取得平衡</li>
     * </ul>
     *
     * @param windowMs  窗口总时长，单位为毫秒，必须 ≥ 1
     * @param bucketMs  单个桶的时间宽度，单位为毫秒，必须 ≥ 1
     * @param slotCount 桶的总数量，范围在 [MIN_BUCKET_SLOTS, MAX_BUCKET_SLOTS] 之间
     * @param ttlMs     Redis 状态的过期时间，单位为毫秒，范围 [1, MAX_STATE_TTL_MS]
     */
    private record WindowConfig(long windowMs, long bucketMs, int slotCount, long ttlMs) {
    }
}
