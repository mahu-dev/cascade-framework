package cc.coderm.cascade.idempotent.store;

import cc.coderm.cascade.idempotent.model.IdempotentRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis Lua 的幂等存储实现（全量脚本模式）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>占位时写入 owner 令牌，建立执行所有权</li>
 *   <li>状态回写与删除都进行 owner + state(CAS) 校验</li>
 *   <li>执行期支持续租，避免长耗时场景 key 过期</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisIdempotentStore implements IdempotentStore {

    private final RedissonClient redissonClient;

    private static final String LUA_TRY_OCCUPY = """
            local key   = KEYS[1]
            local scene = ARGV[1]
            local ttl   = tonumber(ARGV[2])
            local now   = ARGV[3]
            local owner = ARGV[4]
            if redis.call('EXISTS', key) == 0 then
                redis.call('HMSET', key,
                    'state',     'PROCESSING',
                    'scene',     scene,
                    'owner',     owner,
                    'createdAt', now,
                    'updatedAt', now)
                redis.call('PEXPIRE', key, ttl)
                return {'1'}
            end
            local r = redis.call('HGETALL', key)
            table.insert(r, 1, '0')
            return r
            """;

    private static final String LUA_MARK_SUCCEEDED = """
            local key        = KEYS[1]
            local owner      = ARGV[1]
            local result     = ARGV[2]
            local resultType = ARGV[3]
            local ttl        = tonumber(ARGV[4])
            local now        = ARGV[5]
            if redis.call('EXISTS', key) == 0 then
                return 0
            end
            if redis.call('HGET', key, 'owner') ~= owner then
                return 0
            end
            if redis.call('HGET', key, 'state') ~= 'PROCESSING' then
                return 0
            end
            redis.call('HMSET', key,
                'state',      'SUCCEEDED',
                'result',     result,
                'resultType', resultType,
                'updatedAt',  now)
            redis.call('HDEL', key, 'owner')
            redis.call('PEXPIRE', key, ttl)
            return 1
            """;

    private static final String LUA_MARK_FAILED = """
            local key   = KEYS[1]
            local owner = ARGV[1]
            local ttl   = tonumber(ARGV[2])
            local now   = ARGV[3]
            if redis.call('EXISTS', key) == 0 then
                return 0
            end
            if redis.call('HGET', key, 'owner') ~= owner then
                return 0
            end
            if redis.call('HGET', key, 'state') ~= 'PROCESSING' then
                return 0
            end
            redis.call('HMSET', key,
                'state',     'FAILED',
                'updatedAt', now)
            redis.call('HDEL', key, 'owner')
            redis.call('PEXPIRE', key, ttl)
            return 1
            """;

    private static final String LUA_MARK_UNCERTAIN = """
            local key   = KEYS[1]
            local owner = ARGV[1]
            local error = ARGV[2]
            local ttl   = tonumber(ARGV[3])
            local now   = ARGV[4]
            if redis.call('EXISTS', key) == 0 then
                return 0
            end
            if redis.call('HGET', key, 'owner') ~= owner then
                return 0
            end
            if redis.call('HGET', key, 'state') ~= 'PROCESSING' then
                return 0
            end
            redis.call('HMSET', key,
                'state',     'UNCERTAIN',
                'error',     error,
                'updatedAt', now)
            redis.call('HDEL', key, 'owner')
            redis.call('HDEL', key, 'result')
            redis.call('HDEL', key, 'resultType')
            redis.call('PEXPIRE', key, ttl)
            return 1
            """;

    private static final String LUA_DELETE_IF_OWNER = """
            local key   = KEYS[1]
            local owner = ARGV[1]
            if redis.call('EXISTS', key) == 0 then
                return 0
            end
            if redis.call('HGET', key, 'owner') ~= owner then
                return 0
            end
            redis.call('DEL', key)
            return 1
            """;

    private static final String LUA_RENEW_PROCESSING = """
            local key   = KEYS[1]
            local owner = ARGV[1]
            local ttl   = tonumber(ARGV[2])
            local now   = ARGV[3]
            if redis.call('EXISTS', key) == 0 then
                return 0
            end
            if redis.call('HGET', key, 'owner') ~= owner then
                return 0
            end
            if redis.call('HGET', key, 'state') ~= 'PROCESSING' then
                return 0
            end
            redis.call('HSET', key, 'updatedAt', now)
            redis.call('PEXPIRE', key, ttl)
            return 1
            """;

    private static final String LUA_LOAD = """
            local key = KEYS[1]
            if redis.call('EXISTS', key) == 0 then
                return {}
            end
            return redis.call('HGETALL', key)
            """;

    @Override
    public OccupyResult tryOccupy(String key, String scene, long ttlMs, String ownerToken) {
        List<String> result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA_TRY_OCCUPY,
                RScript.ReturnType.LIST,
                List.of(key),
                scene,
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis()),
                ownerToken
        );

        if (result == null || result.isEmpty() || "1".equals(result.get(0))) {
            return OccupyResult.success();
        }
        return OccupyResult.conflict(IdempotentRecord.fromFlatList(result.subList(1, result.size())));
    }

    @Override
    public boolean markSucceeded(String key, String ownerToken, String result, String resultType, long ttlMs) {
        return evalAsBoolean(
                LUA_MARK_SUCCEEDED,
                key,
                ownerToken,
                result != null ? result : "__NULL__",
                resultType != null ? resultType : "",
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis())
        );
    }

    @Override
    public boolean markFailed(String key, String ownerToken, long ttlMs) {
        return evalAsBoolean(
                LUA_MARK_FAILED,
                key,
                ownerToken,
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis())
        );
    }

    @Override
    public boolean markUncertain(String key, String ownerToken, String errorReason, long ttlMs) {
        return evalAsBoolean(
                LUA_MARK_UNCERTAIN,
                key,
                ownerToken,
                errorReason != null ? errorReason : "",
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis())
        );
    }

    @Override
    public boolean deleteIfOwner(String key, String ownerToken) {
        return evalAsBoolean(LUA_DELETE_IF_OWNER, key, ownerToken);
    }

    @Override
    public RenewResult renewProcessing(String key, String ownerToken, long ttlMs) {
        try {
            boolean renewed = evalAsBoolean(
                    LUA_RENEW_PROCESSING,
                    key,
                    ownerToken,
                    String.valueOf(ttlMs),
                    String.valueOf(System.currentTimeMillis())
            );
            return renewed ? RenewResult.RENEWED : RenewResult.OWNERSHIP_LOST;
        } catch (RedisException ex) {
            log.warn("[Idempotent] key={} renew failed transiently via Redis script: {}",
                    key, ex.getMessage());
            return RenewResult.CONTENDED;
        }
    }

    @Override
    public Optional<IdempotentRecord> load(String key) {
        List<String> flat = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_ONLY,
                LUA_LOAD,
                RScript.ReturnType.LIST,
                List.of(key)
        );

        return Optional.ofNullable(IdempotentRecord.fromFlatList(flat));
    }

    private boolean evalAsBoolean(String lua, String key, String... args) {
        Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                lua,
                RScript.ReturnType.LONG,
                List.of(key),
                args
        );
        return result != null && result == 1L;
    }
}
