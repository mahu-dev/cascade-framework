package io.github.cascade.cache.serialization;

/**
 * 缓存序列化器接口
 * 定义缓存值的序列化和反序列化行为
 */
public interface CacheSerializer<T> {
    
    /**
     * 序列化对象为字节数组
     * 
     * @param object 待序列化的对象
     * @return 序列化后的字节数组
     * @throws SerializationException 序列化失败时抛出
     */
    byte[] serialize(T object) throws SerializationException;
    
    /**
     * 反序列化字节数组为对象
     * 
     * @param bytes 待反序列化的字节数组
     * @return 反序列化后的对象
     * @throws SerializationException 反序列化失败时抛出
     */
    T deserialize(byte[] bytes) throws SerializationException;
    
    /**
     * 反序列化字节数组为指定类型的对象
     * 
     * @param bytes 待反序列化的字节数组  
     * @param clazz 目标类型
     * @return 反序列化后的对象
     * @throws SerializationException 反序列化失败时抛出
     */
    <R> R deserialize(byte[] bytes, Class<R> clazz) throws SerializationException;
    
    /**
     * 获取序列化器名称
     * 
     * @return 序列化器名称
     */
    String getName();
    
    /**
     * 获取序列化器版本
     * 
     * @return 版本信息
     */
    default String getVersion() {
        return "1.0";
    }
    
    /**
     * 检查是否支持指定类型的序列化
     * 
     * @param clazz 类型
     * @return 如果支持返回true
     */
    default boolean supports(Class<?> clazz) {
        return true;
    }
    
    /**
     * 获取压缩支持
     * 
     * @return 是否支持压缩
     */
    default boolean supportsCompression() {
        return false;
    }
    
    /**
     * 设置是否启用压缩
     * 
     * @param enabled 是否启用压缩
     */
    default void setCompressionEnabled(boolean enabled) {
        // 默认实现为空
    }
    
    /**
     * 获取序列化性能估计（每秒操作数）
     * 
     * @return 性能估计值，-1表示未知
     */
    default long getEstimatedOpsPerSecond() {
        return -1;
    }
}