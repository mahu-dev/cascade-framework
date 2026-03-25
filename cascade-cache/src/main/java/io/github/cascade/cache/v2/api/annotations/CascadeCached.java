package io.github.cascade.cache.v2.api.annotations;

import io.github.cascade.cache.v2.policy.SyncMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * V2 统一注解入口。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CascadeCached {

    String name() default "";

    String key() default "";

    String condition() default "";

    /**
     * 支持 "30m"、"5m"、"120s" 等格式。
     */
    String ttl() default "";

    /**
     * 支持 "5m"、"30s" 等格式。
     */
    String softTtl() default "";

    long ttlSeconds() default 0;

    long softTtlSeconds() default 0;

    boolean enableL1() default true;

    boolean enableL2() default true;

    boolean autoRefresh() default true;

    SyncMode syncMode() default SyncMode.INVALIDATE;
}
