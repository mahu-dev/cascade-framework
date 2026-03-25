package io.github.cascade.cache.v2.core;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.configuration.CascadeCacheProperties;
import io.github.cascade.cache.core.functional.FunctionalCacheManager;
import io.github.cascade.cache.v2.policy.SyncMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionalCacheManagerBuilderTest {

    @Test
    void shouldBuildCacheWithProgrammaticBuilder() {
        CascadeCacheProperties config = CascadeCacheProperties.defaults();
        config.getL1().setEnabled(true);
        config.getL2().setEnabled(false);
        config.getRefresh().setEnabled(true);
        config.getSync().setEnabled(false);

        FunctionalCacheManager manager = new FunctionalCacheManager(null, config, null);
        try {
            Cache<String, String> cache = manager.newCache("user")
                    .keyType(String.class)
                    .valueType(String.class)
                    .loader(id -> "user-" + id)
                    .ttlSeconds(60)
                    .softTtlSeconds(10)
                    .syncMode(SyncMode.NONE)
                    .autoRefresh(true)
                    .build();

            assertNotNull(cache);
            assertEquals("user-100", cache.get("100").orElse(null));
            assertNotNull(manager.getCache("user"));
            Map<String, Object> diagnostics = manager.diagnostics("user");
            assertEquals(true, diagnostics.get("exists"));
            assertTrue(diagnostics.containsKey("stats.miss"));
        } finally {
            manager.close();
        }
    }
}
