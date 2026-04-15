package io.github.cascade.cache.v2.support;

import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheKeyEncoderTest {

    @Test
    void shouldFailFastWhenKeyIsNull() {
        assertThrows(CacheConfigurationException.class,
                () -> CacheKeyEncoder.encodeKey(null, null));
        assertThrows(CacheConfigurationException.class,
                () -> CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), null));
    }

    @Test
    void shouldKeepStringKeyAsIs() {
        assertEquals("u1", CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), "u1"));
    }

    @Test
    void shouldEscapeControlCharactersAndBackslashReversibly() {
        String raw = "line1\nline2\\tail";
        String encoded = CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), raw);
        assertEquals("line1\\u000Aline2\\\\tail", encoded);
        assertEquals(raw, CacheKeyEncoder.decodeKey(encoded));
    }

    @Test
    void shouldFailFastWhenKeyIsNotString() {
        assertThrows(CacheConfigurationException.class,
                () -> CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), 1L));
        assertThrows(CacheConfigurationException.class,
                () -> CacheKeyEncoder.encodeKey(ObjectMapperHolder.getInstance(), new Object()));
    }

    @Test
    void shouldDecodeInvalidEscapeSafely() {
        assertEquals("abc\\x", CacheKeyEncoder.decodeKey("abc\\x"));
        assertEquals("abc\\u12", CacheKeyEncoder.decodeKey("abc\\u12"));
    }
}
