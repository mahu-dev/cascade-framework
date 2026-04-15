package io.github.cascade.cache.configuration;

import io.github.cascade.cache.v2.api.CacheManager;
import io.github.cascade.cache.v2.facade.CacheAspectSupport;
import io.github.cascade.cache.v2.facade.CacheEvictAspect;
import io.github.cascade.cache.v2.facade.CacheInvocationSnapshotSupport;
import io.github.cascade.cache.v2.facade.CachePutAspect;
import io.github.cascade.cache.v2.facade.CacheableAspect;
import io.github.cascade.cache.v2.facade.FunctionalCacheManager;
import io.github.cascade.cache.v2.loader.CacheLoaderResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CacheAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class));

    @Test
    void shouldRegisterCacheInfrastructureByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CacheLoaderResolver.class);
            assertThat(context).hasSingleBean(FunctionalCacheManager.class);
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context).hasSingleBean(CacheInvocationSnapshotSupport.class);
            assertThat(context).hasSingleBean(CacheAspectSupport.class);
            assertThat(context).hasSingleBean(CacheableAspect.class);
            assertThat(context).hasSingleBean(CachePutAspect.class);
            assertThat(context).hasSingleBean(CacheEvictAspect.class);
        });
    }

    @Test
    void shouldSkipWhenCascadeDisabled() {
        contextRunner
                .withPropertyValues("cascade.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FunctionalCacheManager.class));
    }

    @Test
    void shouldBindProperties() {
        contextRunner
                .withPropertyValues(
                        "cascade.default-cache-name=orders",
                        "cascade.l1.maximum-size=2048",
                        "cascade.refresh.default-refresh-interval-seconds=600"
                )
                .run(context -> {
                    CascadeCacheProperties properties = context.getBean(CascadeCacheProperties.class);
                    assertThat(properties.getDefaultCacheName()).isEqualTo("orders");
                    assertThat(properties.getL1().getMaximumSize()).isEqualTo(2048);
                    assertThat(properties.getRefresh().getDefaultRefreshIntervalSeconds()).isEqualTo(600);
                });
    }
}
