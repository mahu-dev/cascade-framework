package io.github.cascade.serializer;

import io.github.cascade.exception.SerializationException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化器工厂
 */
public class SerializerFactory {
    
    private final Map<String, Serializer> serializers = new ConcurrentHashMap<>();
    private volatile Serializer defaultSerializer;
    
    /**
     * 注册序列化器
     */
    public void register(String name, Serializer serializer) {
        serializers.put(name, serializer);
    }
    
    /**
     * 获取序列化器
     */
    public Serializer get(String name) {
        Serializer serializer = serializers.get(name);
        if (serializer == null) {
            throw new SerializationException("Serializer not found: " + name);
        }
        return serializer;
    }
    
    /**
     * 设置默认序列化器
     */
    public void setDefault(String name) {
        this.defaultSerializer = get(name);
    }
    
    /**
     * 设置默认序列化器
     */
    public void setDefault(Serializer serializer) {
        this.defaultSerializer = serializer;
    }
    
    /**
     * 获取默认序列化器
     */
    public Serializer getDefault() {
        return defaultSerializer;
    }
    
    /**
     * 是否包含指定序列化器
     */
    public boolean contains(String name) {
        return serializers.containsKey(name);
    }
    
    /**
     * 移除序列化器
     */
    public Serializer remove(String name) {
        return serializers.remove(name);
    }
    
    /**
     * 清空所有序列化器
     */
    public void clear() {
        serializers.clear();
        defaultSerializer = null;
    }
}