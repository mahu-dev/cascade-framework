package cc.coderm.cascade.limiter.support;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Redis Lua 脚本执行器，封装 EVALSHA + NOSCRIPT 自动恢复。
 */
public class RedisLuaScriptExecutor {

    private final RedissonClient redissonClient;
    private final ConcurrentMap<String, String> shaCache = new ConcurrentHashMap<>();

    public RedisLuaScriptExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public List<Long> evalLongList(RScript.Mode mode, String scriptSource, List<Object> keys, Object... args) {
        RScript script = redissonClient.getScript(LongCodec.INSTANCE);
        String sha = ensureLoadedSha(scriptSource, script);
        try {
            return script.evalSha(mode, sha, RScript.ReturnType.LIST, keys, args);
        } catch (RuntimeException ex) {
            if (!isNoScriptError(ex)) {
                throw ex;
            }
            String reloadedSha = script.scriptLoad(scriptSource);
            shaCache.put(scriptSource, reloadedSha);
            return script.evalSha(mode, reloadedSha, RScript.ReturnType.LIST, keys, args);
        }
    }

    private String ensureLoadedSha(String scriptSource, RScript script) {
        String cached = shaCache.get(scriptSource);
        if (cached != null) {
            return cached;
        }
        String loaded = script.scriptLoad(scriptSource);
        String previous = shaCache.putIfAbsent(scriptSource, loaded);
        return previous != null ? previous : loaded;
    }

    private static boolean isNoScriptError(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String message = current.getMessage();
            if (message != null && message.contains("NOSCRIPT")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

