package io.github.cascade.cache.annotation;

import java.lang.annotation.*;

/**
 * 标记Cache Bean自动配置CacheLoader
 * 当Cache和CacheLoader的泛型类型K、V匹配时，自动注入
 * 
 * @author cascade
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutoConfigureLoader {
    
    /**
     * 指定要匹配的CacheLoader Bean名称
     * 如果不指定，则按类型自动匹配
     */
    String loaderBean() default "";
    
    /**
     * 是否启用严格类型匹配
     * true: 必须完全匹配泛型类型
     * false: 允许父子类匹配
     */
    boolean strictTypeMatch() default true;
}