package io.github.cascade.cache.v2.support;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 时长解析器，支持 s/m/h/d 后缀。
 */
public final class DurationParser {

    private DurationParser() {
    }

    public static long parseToSeconds(String value, long defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                long ms = Long.parseLong(text.substring(0, text.length() - 2).trim());
                return Math.max(1L, ms / 1000);
            }
            if (text.endsWith("s")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim());
            }
            if (text.endsWith("m")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 60;
            }
            if (text.endsWith("h")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 3600;
            }
            if (text.endsWith("d")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 86400;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
