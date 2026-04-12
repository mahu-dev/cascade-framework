package cc.coderm.cascade.idempotent.config;

import cc.coderm.cascade.idempotent.key.FastJson2IdempotentKeyHasher;
import cc.coderm.cascade.idempotent.key.GsonIdempotentKeyHasher;
import cc.coderm.cascade.idempotent.key.IdempotentKeyHasher;
import cc.coderm.cascade.idempotent.key.JacksonIdempotentKeyHasher;
import cc.coderm.cascade.idempotent.key.UnsupportedDefaultIdempotentKeyHasher;
import cc.coderm.cascade.idempotent.serializer.FastJson2ResultSerializer;
import cc.coderm.cascade.idempotent.serializer.GsonResultSerializer;
import cc.coderm.cascade.idempotent.serializer.JacksonResultSerializer;
import cc.coderm.cascade.idempotent.serializer.ResultSerializer;
import cc.coderm.cascade.idempotent.serializer.UnsupportedResultSerializer;
import cc.coderm.cascade.idempotent.store.IdempotentStore;
import cc.coderm.cascade.idempotent.store.RedisIdempotentStore;
import cc.coderm.cascade.idempotent.store.RedissonIdempotentStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CascadeIdempotentAutoConfigurationTest {

    @Test
    void autoShouldPreferJacksonWhenAvailable() {
        ResultSerializer serializer = CascadeIdempotentAutoConfiguration.resolveResultSerializer(
                CascadeIdempotentProperties.ResultSerializerType.AUTO,
                getClass().getClassLoader());

        assertThat(serializer).isInstanceOf(JacksonResultSerializer.class);
    }

    @Test
    void autoShouldFallbackToFastJson2WhenJacksonMissing() {
        ClassLoader classLoader = new FilteredClassLoader("com.fasterxml.jackson");

        ResultSerializer serializer = CascadeIdempotentAutoConfiguration.resolveResultSerializer(
                CascadeIdempotentProperties.ResultSerializerType.AUTO,
                classLoader);

        assertThat(serializer).isInstanceOf(FastJson2ResultSerializer.class);
    }

    @Test
    void autoShouldFallbackToGsonWhenJacksonAndFastJson2Missing() {
        ClassLoader classLoader = new FilteredClassLoader(
                "com.fasterxml.jackson", "com.alibaba.fastjson2");

        ResultSerializer serializer = CascadeIdempotentAutoConfiguration.resolveResultSerializer(
                CascadeIdempotentProperties.ResultSerializerType.AUTO,
                classLoader);

        assertThat(serializer).isInstanceOf(GsonResultSerializer.class);
    }

    @Test
    void autoShouldUseUnsupportedSerializerWhenNoLibraryAvailable() {
        ClassLoader classLoader = new FilteredClassLoader(
                "com.fasterxml.jackson", "com.alibaba.fastjson2", "com.google.gson");

        ResultSerializer serializer = CascadeIdempotentAutoConfiguration.resolveResultSerializer(
                CascadeIdempotentProperties.ResultSerializerType.AUTO,
                classLoader);

        assertThat(serializer).isInstanceOf(UnsupportedResultSerializer.class);
    }

    @Test
    void explicitSerializerShouldFailFastWhenDependencyMissing() {
        ClassLoader classLoader = new FilteredClassLoader("com.alibaba.fastjson2");

        assertThatThrownBy(() -> CascadeIdempotentAutoConfiguration.resolveResultSerializer(
                CascadeIdempotentProperties.ResultSerializerType.FASTJSON2,
                classLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required class 'com.alibaba.fastjson2.JSON' is missing");
    }

    @Test
    void autoKeyHasherShouldPreferJacksonWhenAvailable() {
        IdempotentKeyHasher hasher = CascadeIdempotentAutoConfiguration.resolveIdempotentKeyHasher(
                CascadeIdempotentProperties.IdempotentKeyHasherType.AUTO,
                getClass().getClassLoader());

        assertThat(hasher).isInstanceOf(JacksonIdempotentKeyHasher.class);
    }

    @Test
    void autoKeyHasherShouldFallbackToFastJson2WhenJacksonMissing() {
        ClassLoader classLoader = new FilteredClassLoader("com.fasterxml.jackson");

        IdempotentKeyHasher hasher = CascadeIdempotentAutoConfiguration.resolveIdempotentKeyHasher(
                CascadeIdempotentProperties.IdempotentKeyHasherType.AUTO,
                classLoader);

        assertThat(hasher).isInstanceOf(FastJson2IdempotentKeyHasher.class);
    }

    @Test
    void autoKeyHasherShouldFallbackToGsonWhenJacksonAndFastJson2Missing() {
        ClassLoader classLoader = new FilteredClassLoader(
                "com.fasterxml.jackson", "com.alibaba.fastjson2");

        IdempotentKeyHasher hasher = CascadeIdempotentAutoConfiguration.resolveIdempotentKeyHasher(
                CascadeIdempotentProperties.IdempotentKeyHasherType.AUTO,
                classLoader);

        assertThat(hasher).isInstanceOf(GsonIdempotentKeyHasher.class);
    }

    @Test
    void autoKeyHasherShouldUseUnsupportedWhenNoLibraryAvailable() {
        ClassLoader classLoader = new FilteredClassLoader(
                "com.fasterxml.jackson", "com.alibaba.fastjson2", "com.google.gson");

        IdempotentKeyHasher hasher = CascadeIdempotentAutoConfiguration.resolveIdempotentKeyHasher(
                CascadeIdempotentProperties.IdempotentKeyHasherType.AUTO,
                classLoader);

        assertThat(hasher).isInstanceOf(UnsupportedDefaultIdempotentKeyHasher.class);
    }

    @Test
    void explicitKeyHasherShouldFailFastWhenDependencyMissing() {
        ClassLoader classLoader = new FilteredClassLoader("com.google.gson");

        assertThatThrownBy(() -> CascadeIdempotentAutoConfiguration.resolveIdempotentKeyHasher(
                CascadeIdempotentProperties.IdempotentKeyHasherType.GSON,
                classLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required class 'com.google.gson.Gson' is missing");
    }

    @Test
    void storeTypeShouldUseRedisScriptStoreByDefaultWhenNull() {
        RedissonClient redissonClient = redissonClientStub();

        IdempotentStore store = CascadeIdempotentAutoConfiguration.resolveIdempotentStore(
                null, redissonClient);

        assertThat(store).isInstanceOf(RedisIdempotentStore.class);
    }

    @Test
    void storeTypeShouldUseRedisScriptStoreWhenConfigured() {
        RedissonClient redissonClient = redissonClientStub();

        IdempotentStore store = CascadeIdempotentAutoConfiguration.resolveIdempotentStore(
                CascadeIdempotentProperties.StoreType.REDIS_SCRIPT, redissonClient);

        assertThat(store).isInstanceOf(RedisIdempotentStore.class);
    }

    @Test
    void storeTypeShouldUseRedissonStoreWhenConfigured() {
        RedissonClient redissonClient = redissonClientStub();

        IdempotentStore store = CascadeIdempotentAutoConfiguration.resolveIdempotentStore(
                CascadeIdempotentProperties.StoreType.REDISSON, redissonClient);

        assertThat(store).isInstanceOf(RedissonIdempotentStore.class);
    }

    private static RedissonClient redissonClientStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                CascadeIdempotentAutoConfigurationTest.class.getClassLoader(),
                new Class[]{RedissonClient.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "RedissonClientStub";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("Not expected in this test: " + method.getName());
                }
        );
    }
}
