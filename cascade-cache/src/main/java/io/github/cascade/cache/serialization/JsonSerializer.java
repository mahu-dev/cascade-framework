package io.github.cascade.cache.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON序列化器实现
 * 基于Jackson的JSON序列化，提供良好的可读性和调试性
 */
public class JsonSerializer<T> implements CacheSerializer<T> {
    
    private static final Logger log = LoggerFactory.getLogger(JsonSerializer.class);
    
    private final ObjectMapper objectMapper;
    private final TypeFactory typeFactory;
    
    public JsonSerializer() {
        this(new ObjectMapper());
    }
    
    public JsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.typeFactory = objectMapper.getTypeFactory();
    }
    
    @Override
    public byte[] serialize(T object) throws SerializationException {
        if (object == null) {
            return null;
        }
        
        try {
            String json = objectMapper.writeValueAsString(object);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize object to JSON", e);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            return (T) objectMapper.readValue(json, Object.class);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize JSON to object", e);
        }
    }
    
    @Override
    public <R> R deserialize(byte[] bytes, Class<R> clazz) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, clazz);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize JSON to " + clazz.getSimpleName(), e);
        }
    }
    
    @Override
    public String getName() {
        return "JsonSerializer";
    }
    
    @Override
    public String getVersion() {
        return "1.0";
    }
    
    @Override
    public boolean supports(Class<?> clazz) {
        try {
            // 尝试检查Jackson是否能够处理这个类型
            typeFactory.constructType(clazz);
            return true;
        } catch (Exception e) {
            log.debug("JSON serializer does not support class: {}", clazz.getName(), e);
            return false;
        }
    }
    
    @Override
    public long getEstimatedOpsPerSecond() {
        return 50000; // JSON序列化相对较慢但通用性好
    }
    
    /**
     * 获取ObjectMapper实例
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}