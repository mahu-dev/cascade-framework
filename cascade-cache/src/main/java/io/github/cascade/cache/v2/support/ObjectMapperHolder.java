package io.github.cascade.cache.v2.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全局共享 ObjectMapper 持有器。
 * <p>
 * ObjectMapper 线程安全（完成配置后可并发复用），统一复用可避免频繁创建带来的初始化开销。
 */
public final class ObjectMapperHolder {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private ObjectMapperHolder() {
    }

    public static ObjectMapper getInstance() {
        return SHARED_MAPPER;
    }
}
