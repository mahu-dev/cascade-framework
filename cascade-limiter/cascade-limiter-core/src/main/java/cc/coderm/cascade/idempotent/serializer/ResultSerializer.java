package cc.coderm.cascade.idempotent.serializer;

import java.lang.reflect.Type;

/**
 * 幂等结果序列化器接口。
 *
 * <p>默认实现为 {@link JacksonResultSerializer}，支持泛型返回值。
 * 若业务使用了 Protobuf / Kryo 等，可自定义实现并注册为 Spring Bean 替换默认实现。
 */
public interface ResultSerializer {

    /**
     * 将业务返回值序列化为字符串。
     *
     * @param result 业务返回值（可以为 null，代表方法返回 void 或 null）
     * @return 序列化结果，null 值序列化为固定占位符
     */
    String serialize(Object result);

    /**
     * 将字符串反序列化为业务返回值。
     *
     * @param json       序列化字符串
     * @param returnType 目标类型（支持泛型）
     * @return 反序列化后的对象
     */
    Object deserialize(String json, Type returnType);

    /**
     * 返回表示 null / void 结果的占位符字符串。
     */
    default String nullPlaceholder() {
        return "__NULL__";
    }
}