package cc.coderm.cascade.limiter.support;

import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.config.KeyAdmissionOverflowPolicy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 基于 Redis ZSET 的 key 准入守卫。
 *
 * <p>核心目标：在高并发随机 key 攻击下，限制 distinct key 总量，避免内存不可控增长。
 */
@Slf4j
public class RedisKeyAdmissionGuard implements KeyAdmissionGuard {

    private static final String KEY_ADMISSION_LUA = """
            local registry = KEYS[1]
            local ttl = tonumber(ARGV[1])
            local maxKeys = tonumber(ARGV[2])
            local candidate = ARGV[3]
            
            local redisTime = redis.call('TIME')
            local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
            
            redis.call('ZREMRANGEBYSCORE', registry, '-inf', now - ttl)
            
            local exists = redis.call('ZSCORE', registry, candidate)
            if exists then
                redis.call('ZADD', registry, now, candidate)
                redis.call('PEXPIRE', registry, ttl)
                return {1, 0}
            end
            
            local current = redis.call('ZCARD', registry)
            if current >= maxKeys then
                local oldest = redis.call('ZRANGE', registry, 0, 0, 'WITHSCORES')
                local waitMs = ttl
                if oldest and #oldest >= 2 then
                    waitMs = math.max(1, ttl - (now - tonumber(oldest[2])))
                end
                return {0, waitMs}
            end
            
            redis.call('ZADD', registry, now, candidate)
            redis.call('PEXPIRE', registry, ttl)
            return {1, 0}
            """;

    private final RedisLuaScriptExecutor scriptExecutor;
    private final CascadeLimiterProperties properties;
    private volatile List<String> cachedBlacklistExpressions = List.of();
    private volatile List<Pattern> cachedBlacklistPatterns = List.of();

    public RedisKeyAdmissionGuard(RedisLuaScriptExecutor scriptExecutor, CascadeLimiterProperties properties) {
        this.scriptExecutor = scriptExecutor;
        this.properties = properties;
    }

    @Override
    public KeyAdmissionDecision admit(String fullKey) {
        if (!properties.isKeyAdmissionEnabled()) {
            return KeyAdmissionDecision.allowed(fullKey);
        }
        if (isBlacklisted(fullKey)) {
            return KeyAdmissionDecision.rejected(0L);
        }

        long maxDistinctKeys = properties.getKeyAdmissionMaxDistinctKeys();
        long ttlMs = Math.max(1L, properties.getKeyAdmissionRegistryTtl().toMillis());
        if (maxDistinctKeys <= 0) {
            return KeyAdmissionDecision.allowed(fullKey);
        }

        List<Long> result = scriptExecutor.evalLongList(
                RScript.Mode.READ_WRITE,
                KEY_ADMISSION_LUA,
                List.of(resolveRegistryKey()),
                ttlMs,
                maxDistinctKeys,
                fullKey
        );

        boolean allowed = result.get(0) == 1L;
        if (allowed) {
            return KeyAdmissionDecision.allowed(fullKey);
        }

        if (properties.getKeyAdmissionOverflowPolicy() == KeyAdmissionOverflowPolicy.COALESCE) {
            String overflowKey = resolveOverflowKey();
            if (!fullKey.equals(overflowKey)) {
                return KeyAdmissionDecision.coalesced(overflowKey);
            }
        }
        return KeyAdmissionDecision.rejected(result.get(1));
    }

    private boolean isBlacklisted(String fullKey) {
        List<Pattern> patterns = blacklistPatterns();
        for (Pattern pattern : patterns) {
            if (pattern.matcher(fullKey).matches()) {
                log.warn("[CascadeLimiter] Key admission blacklist matched. key={}, pattern={}", fullKey, pattern.pattern());
                return true;
            }
        }
        return false;
    }

    private List<Pattern> blacklistPatterns() {
        List<String> expressions = properties.getKeyAdmissionBlacklistPatterns();
        if (CollectionUtils.isEmpty(expressions)) {
            return List.of();
        }
        if (expressions.equals(cachedBlacklistExpressions)) {
            return cachedBlacklistPatterns;
        }
        synchronized (this) {
            expressions = properties.getKeyAdmissionBlacklistPatterns();
            if (CollectionUtils.isEmpty(expressions)) {
                cachedBlacklistExpressions = List.of();
                cachedBlacklistPatterns = List.of();
                return cachedBlacklistPatterns;
            }
            if (expressions.equals(cachedBlacklistExpressions)) {
                return cachedBlacklistPatterns;
            }
            List<Pattern> compiled = new ArrayList<>(expressions.size());
            for (String expression : expressions) {
                if (!StringUtils.hasText(expression)) {
                    continue;
                }
                try {
                    compiled.add(Pattern.compile(expression));
                } catch (PatternSyntaxException ex) {
                    throw new IllegalArgumentException("Invalid key-admission blacklist regex: " + expression, ex);
                }
            }
            cachedBlacklistExpressions = List.copyOf(expressions);
            cachedBlacklistPatterns = List.copyOf(compiled);
            return cachedBlacklistPatterns;
        }
    }

    private String resolveRegistryKey() {
        if (StringUtils.hasText(properties.getKeyAdmissionRegistryKey())) {
            return properties.getKeyAdmissionRegistryKey();
        }
        return properties.getRedisKeyPrefix() + "__admission:keys";
    }

    private String resolveOverflowKey() {
        if (StringUtils.hasText(properties.getKeyAdmissionOverflowKey())) {
            return properties.getKeyAdmissionOverflowKey();
        }
        return properties.getRedisKeyPrefix() + "__admission:overflow";
    }
}
