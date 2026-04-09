package io.github.cascade.cache.v2.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheKeyEncoderTest {

    @Test
    void shouldEncodeNullAsLiteralNull() {
        assertEquals("null", CacheKeyEncoder.encodeKey(null, null));
        assertEquals("null", CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), null));
    }

    @Test
    void shouldKeepLegacyJsonBase64FormatForSimpleType() {
        String key = "u1";
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("\"" + key + "\"").getBytes(StandardCharsets.UTF_8));
        String actual = CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), key);
        assertEquals(expected, actual, "simple key编码应保持与历史实现兼容");
    }

    @Test
    void shouldFallbackToToStringWhenSerializationFails() {
        SelfRefKey key = new SelfRefKey();
        key.self = key;

        String encoded = CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), key);
        assertEquals(key.toString(), encoded);
    }

    private static final class SelfRefKey {
        private SelfRefKey self;
    }
}
