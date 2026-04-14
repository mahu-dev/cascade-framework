package cc.coderm.cascade.limiter.algorithm;

import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.RedisLuaScriptExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;

import java.util.Collections;
import java.util.List;

/**
 * 固定窗口限流算法
 *
 * <p>实现思路：使用 Redis 的 INCR + EXPIREAT 原子操作（Lua 保证），
 * 每个时间窗口以 key:windowStart 为维度计数。
 *
 * <p>优点：实现简单，内存占用极低。
 * <p>缺点：窗口边界存在流量突刺（前一窗口末尾 + 后一窗口开头各 maxPermits）。
 */
@Slf4j
@RequiredArgsConstructor
public class FixedWindowRateLimiter extends AbstractRateLimiter {

    /**
     * Lua 脚本：原子地执行 INCR + 首次设置 TTL。
     * 返回当前窗口已使用次数。
     */
    private static final String FIXED_WINDOW_LUA = """
            local key     = KEYS[1]
            local limit   = tonumber(ARGV[1])
            local window  = tonumber(ARGV[2])
            local permits = tonumber(ARGV[3])
            local current = redis.call('INCRBY', key, permits)
            local ttl = redis.call('PTTL', key)
            if ttl < 0 then
                redis.call('PEXPIRE', key, window)
                ttl = window
            end
            if current > limit then
                return {0, ttl}
            end
            return {1, limit - current}
            """;

    private final RedisLuaScriptExecutor scriptExecutor;


    @Override
    public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
        long windowMs = strategy.getWindow().toMillis();
        List<Long> result = scriptExecutor.evalLongList(
                RScript.Mode.READ_WRITE,
                FIXED_WINDOW_LUA,
                Collections.singletonList(key),
                strategy.getMaxPermits(),
                windowMs,
                (long) permits
        );

        boolean allowed = result.get(0) == 1L;
        long extra = result.get(1);

        if (allowed) {
            return RateLimitResult.allowed(key, AlgorithmType.FIXED_WINDOW, extra);
        }
        return RateLimitResult.rejected(key, AlgorithmType.FIXED_WINDOW, extra);
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        return AlgorithmType.FIXED_WINDOW;
    }
}
