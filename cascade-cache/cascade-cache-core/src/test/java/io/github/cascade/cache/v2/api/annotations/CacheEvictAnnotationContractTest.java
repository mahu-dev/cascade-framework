package io.github.cascade.cache.v2.api.annotations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheEvictAnnotationContractTest {

    @Test
    void shouldKeepDocumentedDefaultsStable() throws NoSuchMethodException {
        assertEquals("", CacheEvict.class.getMethod("value").getDefaultValue());
        assertEquals("", CacheEvict.class.getMethod("key").getDefaultValue());
        assertEquals(false, CacheEvict.class.getMethod("allEntries").getDefaultValue());
        assertEquals(false, CacheEvict.class.getMethod("beforeInvocation").getDefaultValue());
        assertEquals("", CacheEvict.class.getMethod("condition").getDefaultValue());
        assertEquals(true, CacheEvict.class.getMethod("sync").getDefaultValue());
    }
}
