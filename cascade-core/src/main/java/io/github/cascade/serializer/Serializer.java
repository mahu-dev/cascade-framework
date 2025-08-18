package io.github.cascade.serializer;

import io.github.cascade.exception.SerializationException;

/**
 * 序列化器接口
 */
public interface Serializer {
    
    /**
     * 序列化对象
     */
    byte[] serialize(Object obj) throws SerializationException;
    
    /**
     * 反序列化对象
     */
    <T> T deserialize(byte[] bytes, Class<T> type) throws SerializationException;
    
    /**
     * 获取序列化器名称
     */
    String getName();
    
    /**
     * 是否支持该类型
     */
    boolean supports(Class<?> type);
}