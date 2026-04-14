package cc.coderm.cascade.idempotent.serializer;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * 基于 Gson 的结果序列化器。
 */
public class GsonResultSerializer implements ResultSerializer {

    private final Gson gson;

    public GsonResultSerializer() {
        this(new Gson());
    }

    public GsonResultSerializer(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String serialize(Object result) {
        if (result == null) {
            return nullPlaceholder();
        }
        try {
            return gson.toJson(result);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Failed to serialize idempotent cached result with Gson.", ex);
        }
    }

    @Override
    public Object deserialize(String json, Type returnType) {
        if (json == null || nullPlaceholder().equals(json)) {
            return null;
        }
        try {
            return gson.fromJson(json, returnType);
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("Failed to deserialize idempotent cached result with Gson.", ex);
        }
    }
}
