package io.github.cascade.cache.v2.store.model;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一缓存记录模型。
 *
 * @param <V> 值类型
 */
@Setter
@Getter
public class CacheRecord<V> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务值本体。
     */
    private V value;

    /**
     * 逻辑版本号。
     * <p>
     * 用于跨节点同步时判定事件新旧，避免旧事件覆盖新值。
     */
    private long version;

    /**
     * 最近一次写入时间戳。
     * <p>
     * 主要用于诊断和刷新窗口判断。
     */
    private long writeTimeMs;

    /**
     * 软过期时间戳。
     * <p>
     * 超过该时间后，读请求仍可返回旧值，但会触发异步刷新。
     */
    private long softExpireAtMs;

    /**
     * 硬过期时间戳。
     * <p>
     * 超过该时间后记录视为完全失效，不再参与返回或回填。
     */
    private long hardExpireAtMs;

    /**
     * 写入该记录的节点标识。
     * <p>
     * 用于同步事件去环和问题排查。
     */
    private String sourceNodeId;

    public CacheRecord() {
    }

    public CacheRecord(V value,
                       long version,
                       long writeTimeMs,
                       long softExpireAtMs,
                       long hardExpireAtMs,
                       String sourceNodeId) {
        this.value = value;
        this.version = version;
        this.writeTimeMs = writeTimeMs;
        this.softExpireAtMs = softExpireAtMs;
        this.hardExpireAtMs = hardExpireAtMs;
        this.sourceNodeId = sourceNodeId;
    }

}
