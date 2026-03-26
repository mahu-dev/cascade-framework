package io.github.cascade.cache.v2.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 绑定CacheLoader到指定缓存定义（三元组：cacheName + keyType + valueType）。
 * <p>
 * 仅标注了该注解的CacheLoader才会参与自动发现注册。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CacheLoaderBindings.class)
public @interface CacheLoaderBinding {

    /**
     * 绑定的缓存名称。
     */
    String cacheName();

    /**
     * 绑定的键类型。
     */
    Class<?> keyType();

    /**
     * 绑定的值类型。
     */
    Class<?> valueType();
}
