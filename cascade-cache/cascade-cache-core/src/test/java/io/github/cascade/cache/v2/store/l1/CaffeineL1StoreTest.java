package io.github.cascade.cache.v2.store.l1;

import io.github.cascade.cache.v2.store.model.CacheRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaffeineL1StoreTest {

    @Test
    void shouldSupportInitialCapacityHint() {
        CaffeineL1Store<String, String> store = new CaffeineL1Store<>(100, false, -1, -1, 32);
        try {
            store.put("k0", record("v0"));
            assertEquals("v0", store.get("k0").map(CacheRecord::getValue).orElse(null));
        } finally {
            store.close();
        }
    }

    @Test
    void shouldExpireAfterWriteWhenConfigured() throws InterruptedException {
        CaffeineL1Store<String, String> store = new CaffeineL1Store<>(100, false, 1, -1);
        try {
            store.put("k1", record("v1"));
            assertEquals("v1", store.get("k1").map(CacheRecord::getValue).orElse(null));

            Thread.sleep(1200L);

            assertTrue(store.get("k1").isEmpty(), "写后过期时间应生效");
        } finally {
            store.close();
        }
    }

    @Test
    void shouldExpireAfterAccessWhenConfigured() throws InterruptedException {
        CaffeineL1Store<String, String> store = new CaffeineL1Store<>(100, false, -1, 1);
        try {
            store.put("k2", record("v2"));

            Thread.sleep(700L);
            assertEquals("v2", store.get("k2").map(CacheRecord::getValue).orElse(null));

            Thread.sleep(700L);
            assertEquals("v2", store.get("k2").map(CacheRecord::getValue).orElse(null));

            Thread.sleep(1200L);
            assertTrue(store.get("k2").isEmpty(), "访问后过期时间应生效");
        } finally {
            store.close();
        }
    }

    private static CacheRecord<String> record(String value) {
        long now = System.currentTimeMillis();
        return new CacheRecord<>(value, 1L, now, now + 60_000L, now + 60_000L, "test");
    }
}
