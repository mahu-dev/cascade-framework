package io.github.cascade.cache.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 序列化器工厂
 * 根据配置创建和管理不同类型的序列化器
 */
public class SerializerFactory {
    
    private static final Logger log = LoggerFactory.getLogger(SerializerFactory.class);
    
    public enum SerializerType {
        JSON("json", "JSON序列化器，可读性好，兼容性强"),
        KRYO("kryo", "Kryo序列化器，高性能二进制序列化"),
        PROTOBUF("protobuf", "Protocol Buffers序列化器，跨语言支持");
        
        private final String name;
        private final String description;
        
        SerializerType(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public String getName() {
            return name;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private static final ConcurrentMap<String, CacheSerializer<?>> serializerCache = new ConcurrentHashMap<>();
    private static volatile ObjectMapper defaultObjectMapper;
    
    /**
     * 创建JSON序列化器
     */
    @SuppressWarnings("unchecked")
    public static <T> CacheSerializer<T> createJsonSerializer() {
        return (CacheSerializer<T>) serializerCache.computeIfAbsent("json", 
            k -> new JsonSerializer<>(getDefaultObjectMapper()));
    }
    
    /**
     * 创建JSON序列化器（自定义ObjectMapper）
     */
    public static <T> CacheSerializer<T> createJsonSerializer(ObjectMapper objectMapper) {
        return new JsonSerializer<>(objectMapper);
    }
    
    /**
     * 创建Kryo序列化器
     */
    @SuppressWarnings("unchecked")
    public static <T> CacheSerializer<T> createKryoSerializer() {
        return (CacheSerializer<T>) serializerCache.computeIfAbsent("kryo", 
            k -> {
                try {
                    // 检查Kryo是否在类路径中
                    Class.forName("com.esotericsoftware.kryo.Kryo");
                    return new KryoSerializer<>();
                } catch (ClassNotFoundException e) {
                    log.warn("Kryo not found in classpath, falling back to JSON serializer");
                    return new JsonSerializer<>(getDefaultObjectMapper());
                }
            });
    }
    
    /**
     * 创建带压缩的Kryo序列化器
     */
    public static <T> CacheSerializer<T> createKryoSerializer(boolean enableCompression) {
        try {
            Class.forName("com.esotericsoftware.kryo.Kryo");
            KryoSerializer<T> serializer = new KryoSerializer<>();
            serializer.setCompressionEnabled(enableCompression);
            return serializer;
        } catch (ClassNotFoundException e) {
            log.warn("Kryo not found in classpath, falling back to JSON serializer");
            return createJsonSerializer();
        }
    }
    
    /**
     * 根据类型创建序列化器
     */
    public static <T> CacheSerializer<T> create(SerializerType type) {
        return switch (type) {
            case JSON -> createJsonSerializer();
            case KRYO -> createKryoSerializer();
            case PROTOBUF -> throw new UnsupportedOperationException("Protobuf serializer not implemented yet");
        };
    }
    
    /**
     * 根据名称创建序列化器
     */
    public static <T> CacheSerializer<T> create(String typeName) {
        try {
            SerializerType type = SerializerType.valueOf(typeName.toUpperCase());
            return create(type);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown serializer type: {}, falling back to JSON", typeName);
            return createJsonSerializer();
        }
    }
    
    /**
     * 获取最佳序列化器（基于性能和可用性）
     */
    public static <T> CacheSerializer<T> getBest() {
        // 优先选择Kryo，如果不可用则使用JSON
        try {
            Class.forName("com.esotericsoftware.kryo.Kryo");
            return createKryoSerializer();
        } catch (ClassNotFoundException e) {
            log.info("Kryo not available, using JSON serializer");
            return createJsonSerializer();
        }
    }
    
    /**
     * 根据数据大小选择合适的序列化器
     */
    public static <T> CacheSerializer<T> forDataSize(long estimatedSizeBytes) {
        if (estimatedSizeBytes > 1024) { // 大于1KB的数据考虑压缩
            return createKryoSerializer(true);
        } else {
            return getBest();
        }
    }
    
    /**
     * 获取默认ObjectMapper
     */
    private static ObjectMapper getDefaultObjectMapper() {
        if (defaultObjectMapper == null) {
            synchronized (SerializerFactory.class) {
                if (defaultObjectMapper == null) {
                    defaultObjectMapper = new ObjectMapper();
                    // 配置ObjectMapper
                    defaultObjectMapper.findAndRegisterModules();
                }
            }
        }
        return defaultObjectMapper;
    }
    
    /**
     * 设置默认ObjectMapper
     */
    public static void setDefaultObjectMapper(ObjectMapper objectMapper) {
        defaultObjectMapper = objectMapper;
    }
    
    /**
     * 清除缓存的序列化器实例
     */
    public static void clearCache() {
        serializerCache.clear();
    }
    
    /**
     * 获取所有支持的序列化器类型
     */
    public static SerializerType[] getSupportedTypes() {
        return SerializerType.values();
    }
}