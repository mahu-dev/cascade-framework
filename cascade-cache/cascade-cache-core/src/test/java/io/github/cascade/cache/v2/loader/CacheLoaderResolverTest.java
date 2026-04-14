package io.github.cascade.cache.v2.loader;

import io.github.cascade.cache.v2.api.CacheLoader;
import io.github.cascade.cache.v2.api.annotations.CacheLoaderBinding;
import io.github.cascade.cache.v2.common.exception.CacheConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheLoaderResolverTest {

    @Test
    void shouldResolveByExactBindingAndCacheName() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ExactBindingConfig.class)) {
            CacheLoaderResolver resolver = newResolver(context, true);

            CacheLoader<String, String> users = resolver.resolveCacheLoader("users", String.class, String.class);
            CacheLoader<String, String> orders = resolver.resolveCacheLoader("orders", String.class, String.class);

            assertNotNull(users);
            assertNotNull(orders);
            assertEquals("users-k1", users.apply("k1"));
            assertEquals("orders-k1", orders.apply("k1"));
            assertNull(resolver.resolveCacheLoader("missing", String.class, String.class));
        }
    }

    @Test
    void shouldFailFastWhenLoaderBeanMissingBinding() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(MissingBindingConfig.class)) {
            CacheLoaderResolver resolver = new CacheLoaderResolver();
            assertThrows(CacheConfigurationException.class, () -> resolver.setApplicationContext(context));
        }
    }

    @Test
    void shouldFailFastWhenDuplicateBindingDiscovered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DuplicateBindingConfig.class)) {
            CacheLoaderResolver resolver = new CacheLoaderResolver();
            assertThrows(CacheConfigurationException.class, () -> resolver.setApplicationContext(context));
        }
    }

    @Test
    void shouldFilterDiscoveredLoaderWhenNotAllowed() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ExactBindingConfig.class)) {
            CacheLoaderResolver resolver = newResolver(context, true);

            assertNull(resolver.resolveCacheLoader("users", String.class, String.class, false));

            resolver.registerLoader("users", String.class, String.class, key -> "explicit-" + key);
            CacheLoader<String, String> explicit = resolver.resolveCacheLoader("users", String.class, String.class, false);
            assertNotNull(explicit);
            assertEquals("explicit-k1", explicit.apply("k1"));
        }
    }

    @Test
    void shouldSkipAutoDiscoveryWhenDisabledButStillAllowProgrammaticRegistration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(MissingBindingConfig.class)) {
            CacheLoaderResolver resolver = newResolver(context, false);
            assertNull(resolver.resolveCacheLoader("users", String.class, String.class));

            resolver.registerLoader("users", String.class, String.class, key -> "users-" + key);
            CacheLoader<String, String> users = resolver.resolveCacheLoader("users", String.class, String.class);
            assertNotNull(users);
            assertEquals("users-k2", users.apply("k2"));
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldGuardProgrammaticLoaderTypeAtRuntime() {
        CacheLoaderResolver resolver = new CacheLoaderResolver();

        resolver.registerLoader("users", String.class, String.class, key -> "users-" + key);

        CacheLoader<String, String> users = resolver.resolveCacheLoader("users", String.class, String.class);
        assertNotNull(users);
        assertEquals("users-k1", users.apply("k1"));

        CacheLoader rawUsers = users;
        assertThrows(CacheConfigurationException.class, () -> rawUsers.apply(1L));

        CacheLoader<String, String> wrongReturnTypeLoader = (CacheLoader<String, String>) (CacheLoader) ((CacheLoader<String, Object>) key -> 123L);
        resolver.registerLoader("broken", String.class, String.class, wrongReturnTypeLoader);

        CacheLoader<String, String> broken = resolver.resolveCacheLoader("broken", String.class, String.class);
        assertNotNull(broken);
        assertThrows(CacheConfigurationException.class, () -> broken.apply("k1"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldNormalizePrimitiveAndWrapperValueTypeBinding() {
        CacheLoaderResolver resolver = new CacheLoaderResolver();
        Class<Integer> primitiveIntType = (Class<Integer>) (Class) int.class;

        resolver.registerLoader("numbers", String.class, primitiveIntType, key -> 7);

        CacheLoader<String, Integer> wrapperBound = resolver.resolveCacheLoader("numbers", String.class, Integer.class);
        assertNotNull(wrapperBound);
        assertEquals(7, wrapperBound.apply("k1"));

        CacheLoader<String, Integer> primitiveBound = resolver.resolveCacheLoader("numbers", String.class, primitiveIntType);
        assertNotNull(primitiveBound);
        assertEquals(7, primitiveBound.apply("k2"));
    }

    @Test
    void shouldRejectProgrammaticDuplicateBinding() {
        CacheLoaderResolver resolver = new CacheLoaderResolver();
        resolver.registerLoader("users", String.class, String.class, key -> "v1-" + key);

        assertThrows(CacheConfigurationException.class,
                () -> resolver.registerLoader("users", String.class, String.class, key -> "v2-" + key));
    }

    private static CacheLoaderResolver newResolver(AnnotationConfigApplicationContext context, boolean autoDiscover) {
        CacheLoaderResolver resolver = new CacheLoaderResolver();
        resolver.setAutoDiscoverEnabled(autoDiscover);
        resolver.setApplicationContext(context);
        return resolver;
    }

    @Configuration
    static class ExactBindingConfig {
        @Bean("usersLoader")
        CacheLoader<String, String> usersLoader() {
            return new UsersLoader();
        }

        @Bean("ordersLoader")
        CacheLoader<String, String> ordersLoader() {
            return new OrdersLoader();
        }
    }

    @Configuration
    static class MissingBindingConfig {
        @Bean("missingBindingLoader")
        CacheLoader<String, String> missingBindingLoader() {
            return new MissingBindingLoader();
        }
    }

    @Configuration
    static class DuplicateBindingConfig {
        @Bean("duplicateA")
        CacheLoader<String, String> duplicateA() {
            return new DuplicateLoaderA();
        }

        @Bean("duplicateB")
        CacheLoader<String, String> duplicateB() {
            return new DuplicateLoaderB();
        }
    }

    @CacheLoaderBinding(cacheName = "users", keyType = String.class, valueType = String.class)
    static class UsersLoader implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "users-" + key;
        }
    }

    @CacheLoaderBinding(cacheName = "orders", keyType = String.class, valueType = String.class)
    static class OrdersLoader implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "orders-" + key;
        }
    }

    static class MissingBindingLoader implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "missing-" + key;
        }
    }

    @CacheLoaderBinding(cacheName = "dup", keyType = String.class, valueType = String.class)
    static class DuplicateLoaderA implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "a-" + key;
        }
    }

    @CacheLoaderBinding(cacheName = "dup", keyType = String.class, valueType = String.class)
    static class DuplicateLoaderB implements CacheLoader<String, String> {
        @Override
        public String apply(String key) {
            return "b-" + key;
        }
    }
}
