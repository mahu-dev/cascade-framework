package cc.coderm.cascade.idempotent.serializer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;

import java.lang.reflect.Type;

/**
 * 基于 FastJson2 的结果序列化器。
 */
public class FastJson2ResultSerializer implements ResultSerializer {

    @Override
    public String serialize(Object result) {
        if (result == null) {
            return nullPlaceholder();
        }
        try {
            return JSON.toJSONString(result);
        } catch (JSONException ex) {
            throw new IllegalArgumentException("Failed to serialize idempotent cached result with FastJson2.", ex);
        }
    }

    @Override
    public Object deserialize(String json, Type returnType) {
        if (json == null || nullPlaceholder().equals(json)) {
            return null;
        }
        try {
            return JSON.parseObject(json, returnType);
        } catch (JSONException ex) {
            throw new IllegalArgumentException("Failed to deserialize idempotent cached result with FastJson2.", ex);
        }
    }
}
