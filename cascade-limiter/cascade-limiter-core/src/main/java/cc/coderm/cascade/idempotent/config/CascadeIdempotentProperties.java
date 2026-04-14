package cc.coderm.cascade.idempotent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等模块配置属性。
 *
 * <pre>
 * cascade:
 *   idempotent:
 *     redis-key-prefix: "cascade:idempotent:"
 *     metrics-enabled: true
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 04:58
 * =============================
 */
@Data
@ConfigurationProperties(prefix = "cascade.idempotent")
public class CascadeIdempotentProperties {

    /**
     * Redis key 统一前缀
     */
    private String redisKeyPrefix = "cascade:idempotent:";

    /**
     * 是否启用 Micrometer 指标
     */
    private boolean metricsEnabled = true;

    /**
     * 从 HTTP 请求中提取幂等键时使用的 Header 名称（对应 SpEL 变量 #idempotentHeader）。
     */
    private String idempotentHeaderName = "Idempotency-Key";

    /**
     * 结果回放序列化器类型。
     * AUTO 模式下按 JACKSON -> FASTJSON2 -> GSON 的顺序自动选择可用实现。
     */
    private ResultSerializerType resultSerializer = ResultSerializerType.AUTO;

    /**
     * 默认幂等 key 哈希器类型。
     * AUTO 模式下按 JACKSON -> FASTJSON2 -> GSON 的顺序自动选择可用实现。
     */
    private IdempotentKeyHasherType keyHasher = IdempotentKeyHasherType.AUTO;

    /**
     * 幂等存储实现类型。
     * REDIS_SCRIPT: 全量 Lua 脚本路径（稳健兼容）
     * REDISSON: 使用脚本 SHA 缓存优化路径（更高性能）
     */
    private StoreType storeType = StoreType.REDIS_SCRIPT;

    /**
     * WAIT 策略是否启用 Redis Pub/Sub 跨实例通知。
     */
    private boolean waitPubSubEnabled = true;

    /**
     * WAIT 策略跨实例通知主题。
     */
    private String waitPubSubTopic = "cascade:idempotent:completion";

    public enum ResultSerializerType {
        AUTO,
        JACKSON,
        FASTJSON2,
        GSON
    }

    public enum IdempotentKeyHasherType {
        AUTO,
        JACKSON,
        FASTJSON2,
        GSON
    }

    public enum StoreType {
        REDIS_SCRIPT,
        REDISSON
    }
}
