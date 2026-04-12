package cc.coderm.cascade.idempotent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储在 Redis 中的幂等记录。
 *
 * <p>Redis Hash 结构：
 * <pre>
 *   key   = cascade:idempotent:{scene}:{idempotentKey}
 *   field = state        → PROCESSING / SUCCEEDED / FAILED
 *   field = result       → JSON 序列化的返回值（null 值也序列化）
 *   field = resultType   → 返回值的完整类型名（反序列化用）
 *   field = scene        → 场景标识
 *   field = owner        → PROCESSING 持有者令牌
 *   field = createdAt    → 首次创建时间戳（ms）
 *   field = updatedAt    → 最后更新时间戳（ms）
 * </pre>
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 16:45
 * =============================
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentRecord {

    private IdempotentState state;

    /**
     * JSON 序列化后的业务返回值，state=PROCESSING 时为 null
     */
    private String result;

    /**
     * 返回值的完整类型名，用于反序列化
     */
    private String resultType;

    /**
     * 场景标识
     */
    private String scene;

    /**
     * PROCESSING 状态持有者令牌（其他终态可为空）
     */
    private String owner;

    /**
     * 失败/不确定状态下的错误摘要（可选）
     */
    private String error;

    /**
     * 首次创建时间（毫秒时间戳）
     */
    private long createdAt;

    /**
     * 最后更新时间（毫秒时间戳）
     */
    private long updatedAt;

    public boolean isProcessing() {
        return state == IdempotentState.PROCESSING;
    }

    public boolean isSucceeded() {
        return state == IdempotentState.SUCCEEDED;
    }

    public boolean isFailed() {
        return state == IdempotentState.FAILED;
    }

    public boolean isUncertain() {
        return state == IdempotentState.UNCERTAIN;
    }

    /**
     * 从 Redis Hash Map 反序列化为幂等记录。
     *
     * <p>这是从 Redisson RMap 读取数据的专用工厂方法。
     *
     * @param map Redis Hash 字段映射（允许为 null 或空）
     * @return 解析后的幂等记录，如果 map 为空或无有效状态则返回 null
     */
    public static IdempotentRecord fromMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        IdempotentState state = parseState(map.get("state"));
        if (state == null) {
            return null;
        }

        return builder()
                .state(state)
                .result(map.get("result"))
                .resultType(map.getOrDefault("resultType", ""))
                .scene(map.getOrDefault("scene", ""))
                .owner(map.get("owner"))
                .error(map.get("error"))
                .createdAt(parseTimestamp(map.get("createdAt")))
                .updatedAt(parseTimestamp(map.get("updatedAt")))
                .build();
    }

    /**
     * 从 Redis HGETALL 扁平列表反序列化为幂等记录。
     *
     * <p>Redis HGETALL 返回 [field1, value1, field2, value2, ...] 格式的扁平列表，
     * 此方法将其转换为幂等记录对象。
     *
     * <p>这是从原生 Redis 命令（Jedis/Lettuce）读取数据的专用工厂方法。
     *
     * @param flatList HGETALL 返回的扁平列表（允许为 null 或空）
     * @return 解析后的幂等记录，如果列表为空或无有效状态则返回 null
     */
    public static IdempotentRecord fromFlatList(List<String> flatList) {
        if (flatList == null || flatList.isEmpty()) {
            return null;
        }

        Map<String, String> map = flattenListToMap(flatList);
        return fromMap(map);
    }

    /**
     * 将扁平列表转换为 Map。
     *
     * @param flatList [field1, value1, field2, value2, ...] 格式的列表
     * @return 字段映射
     */
    private static Map<String, String> flattenListToMap(List<String> flatList) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < flatList.size(); i += 2) {
            String field = flatList.get(i);
            String value = flatList.get(i + 1);
            if (field != null) {
                map.put(field, value);
            }
        }
        return map;
    }

    /**
     * 解析状态枚举。
     *
     * @param stateStr 状态字符串
     * @return 状态枚举，无效时返回 null
     */
    private static IdempotentState parseState(String stateStr) {
        if (stateStr == null || stateStr.isEmpty()) {
            return null;
        }
        try {
            return IdempotentState.valueOf(stateStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 解析时间戳。
     *
     * @param value 时间戳字符串（毫秒）
     * @return 时间戳长整型，解析失败时返回 0
     */
    private static long parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
