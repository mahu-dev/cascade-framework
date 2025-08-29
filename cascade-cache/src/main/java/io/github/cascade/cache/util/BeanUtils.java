package io.github.cascade.cache.util;

import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;

import java.util.HashMap;
import java.util.Map;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2025/8/29
 * Time: 11:25
 * =============================
 */
public class BeanUtils {

    /**
     * 根据泛型精确获取 Bean
     *
     * @param applicationContext Spring 容器
     * @param rawClass           原始类（比如 CacheLoader.class）
     * @param generics           泛型参数（比如 String.class, Object.class）
     * @return Map<String, T> BeanName -> Bean实例
     */
    @SuppressWarnings("unchecked")
    public static <T> Map<String, T> getBeansOfGenericType(ApplicationContext applicationContext,
                                                           Class<T> rawClass,
                                                           Class<?>... generics) {
        Map<String, T> result = new HashMap<>();

        // 构造 ResolvableType，例如 CacheLoader<String, Object>
        ResolvableType type = ResolvableType.forClassWithGenerics(rawClass, generics);

        // 遍历容器中的 beanName
        String[] beanNames = applicationContext.getBeanNamesForType(rawClass);
        for (String beanName : beanNames) {
            ResolvableType beanType = applicationContext.getType(beanName) != null
                    ? ResolvableType.forType(applicationContext.getType(beanName))
                    : null;

            if (beanType != null && beanType.isAssignableFrom(type)) {
                result.put(beanName, (T) applicationContext.getBean(beanName));
            }
        }

        return result;
    }
}
