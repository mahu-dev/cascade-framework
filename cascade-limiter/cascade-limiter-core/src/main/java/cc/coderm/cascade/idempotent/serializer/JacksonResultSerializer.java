package cc.coderm.cascade.idempotent.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;

/**
 * 基于 Jackson 的结果序列化器，支持泛型类型（如 {@code List<OrderDTO>}）。
 */
public class JacksonResultSerializer implements ResultSerializer {

    private final ObjectMapper objectMapper;

    public JacksonResultSerializer() {
        this(new ObjectMapper());
    }

    public JacksonResultSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(Object result) {
        if (result == null) {
            return nullPlaceholder();
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize idempotent cached result.", ex);
        }
    }

    @Override
    public Object deserialize(String json, Type returnType) {
        if (nullPlaceholder().equals(json) || json == null) {
            return null;
        }
        try {
            // 使用 constructType 支持泛型（ParameterizedType）
            JavaType javaType = objectMapper.getTypeFactory().constructType(returnType);
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to deserialize idempotent cached result.", ex);
        }
    }
}
