package cc.coderm.cascade.idempotent.serializer;

import java.lang.reflect.Type;

/**
 * 在缺少默认序列化实现时提供明确失败提示。
 */
public class UnsupportedResultSerializer implements ResultSerializer {

    @Override
    public String serialize(Object result) {
        if (result == null) {
            return nullPlaceholder();
        }
        throw unsupported();
    }

    @Override
    public Object deserialize(String json, Type returnType) {
        if (json == null || nullPlaceholder().equals(json)) {
            return null;
        }
        throw unsupported();
    }

    private IllegalStateException unsupported() {
        return new IllegalStateException(
                "No default ResultSerializer available. Add Jackson to classpath "
                        + "or provide a custom ResultSerializer bean.");
    }
}
