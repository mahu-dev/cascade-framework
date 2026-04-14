package cc.coderm.cascade.idempotent.key;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 生成稳定 JSON 字符串（对象字段按 key 排序），用于幂等 key 哈希。
 */
final class CanonicalJsonSupport {

    private CanonicalJsonSupport() {
    }

    static String canonicalize(Object value) {
        StringBuilder builder = new StringBuilder(256);
        append(value, builder);
        return builder.toString();
    }

    private static void append(Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof CharSequence str) {
            builder.append(quote(str.toString()));
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendMap(map, builder);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            appendIterable(iterable, builder);
            return;
        }
        if (value.getClass().isArray()) {
            appendArray(value, builder);
            return;
        }
        // 兜底路径：按字符串处理，避免未知类型导致哈希中断
        builder.append(quote(String.valueOf(value)));
    }

    private static void appendMap(Map<?, ?> map, StringBuilder builder) {
        builder.append('{');
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
        boolean first = true;
        for (Map.Entry<?, ?> entry : entries) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quote(String.valueOf(entry.getKey())));
            builder.append(':');
            append(entry.getValue(), builder);
        }
        builder.append('}');
    }

    private static void appendIterable(Iterable<?> iterable, StringBuilder builder) {
        builder.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            append(item, builder);
        }
        builder.append(']');
    }

    private static void appendArray(Object array, StringBuilder builder) {
        builder.append('[');
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            append(Array.get(array, i), builder);
        }
        builder.append(']');
    }

    private static String quote(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 2);
        escaped.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }
}
