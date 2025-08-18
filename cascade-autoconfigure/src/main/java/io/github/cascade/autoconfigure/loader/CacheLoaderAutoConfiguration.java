package io.github.cascade.autoconfigure.loader;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheLoader;
import io.github.cascade.cache.api.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * CacheLoader自动配置类
 * 自动将Spring Bean的CacheLoader根据泛型类型匹配到对应的Cache实例
 *
 * @author cascade
 */
@Configuration
@ConditionalOnBean(CacheManager.class)
public class CacheLoaderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CacheLoaderAutoConfiguration.class);

    @Bean
    public CacheLoaderInjectionPostProcessor cacheLoaderInjectionPostProcessor() {
        logger.debug("创建CacheLoader注入处理器");
        return new CacheLoaderInjectionPostProcessor();
    }

    /**
     * Bean后处理器，负责在Cache Bean创建后自动注入匹配的CacheLoader
     */
    public static class CacheLoaderInjectionPostProcessor implements BeanPostProcessor, ApplicationContextAware {

        private ApplicationContext applicationContext;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            this.applicationContext = applicationContext;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            // 检查是否是Cache实例
//            logger.info("正在处理Cache Bean: {}", beanName);
            if (bean instanceof Cache) {
                tryInjectCacheLoader((Cache<?, ?>) bean, beanName);
            }
            return bean;
        }

        @SuppressWarnings("unchecked")
        private void tryInjectCacheLoader(Cache<?, ?> cache, String cacheBeanName) {
            try {
                // 获取Cache的泛型类型
                TypeInfo cacheTypeInfo = extractCacheGenericTypes(cache);
                if (cacheTypeInfo == null) {
                    logger.debug("无法确定Cache {} 的泛型类型，跳过CacheLoader注入", cacheBeanName);
                    return;
                }

                // 查找匹配的CacheLoader
                Map<String, CacheLoader> loaderBeans = applicationContext.getBeansOfType(CacheLoader.class);

                for (Map.Entry<String, CacheLoader> entry : loaderBeans.entrySet()) {
                    String loaderBeanName = entry.getKey();
                    CacheLoader<?, ?> loader = entry.getValue();

                    TypeInfo loaderTypeInfo = extractLoaderGenericTypes(loader);
                    if (loaderTypeInfo != null && typesMatch(cacheTypeInfo, loaderTypeInfo)) {
                        // 类型匹配，执行注入
                        injectLoaderToCache((Cache<Object, Object>) cache, (CacheLoader<Object, Object>) loader,
                                cacheBeanName, loaderBeanName);
                        break; // 只注入第一个匹配的loader
                    }
                }
            } catch (Exception e) {
                logger.warn("为Cache {} 注入CacheLoader时发生错误: {}", cacheBeanName, e.getMessage());
            }
        }

        /**
         * 提取Cache的泛型类型信息
         */
        private TypeInfo extractCacheGenericTypes(Cache<?, ?> cache) {
            try {
                // 尝试从类的泛型接口中获取类型信息
                Class<?> cacheClass = cache.getClass();
                Type[] genericInterfaces = cacheClass.getGenericInterfaces();

                for (Type genericInterface : genericInterfaces) {
                    if (genericInterface instanceof ParameterizedType) {
                        ParameterizedType paramType = (ParameterizedType) genericInterface;
                        if (paramType.getRawType().equals(Cache.class)) {
                            Type[] typeArgs = paramType.getActualTypeArguments();
                            if (typeArgs.length == 2) {
                                return new TypeInfo((Class<?>) typeArgs[0], (Class<?>) typeArgs[1]);
                            }
                        }
                    }
                }

                // 尝试从父类中获取
                Type genericSuperclass = cacheClass.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    Type[] typeArgs = paramType.getActualTypeArguments();
                    if (typeArgs.length >= 2) {
                        return new TypeInfo((Class<?>) typeArgs[0], (Class<?>) typeArgs[1]);
                    }
                }
            } catch (Exception e) {
                logger.debug("提取Cache泛型类型时出错: {}", e.getMessage());
            }
            return null;
        }

        /**
         * 提取CacheLoader的泛型类型信息
         */
        private TypeInfo extractLoaderGenericTypes(CacheLoader<?, ?> loader) {
            try {
                Class<?> loaderClass = loader.getClass();
                Type[] genericInterfaces = loaderClass.getGenericInterfaces();

                for (Type genericInterface : genericInterfaces) {
                    if (genericInterface instanceof ParameterizedType) {
                        ParameterizedType paramType = (ParameterizedType) genericInterface;
                        if (paramType.getRawType().equals(CacheLoader.class)) {
                            Type[] typeArgs = paramType.getActualTypeArguments();
                            if (typeArgs.length == 2) {
                                return new TypeInfo((Class<?>) typeArgs[0], (Class<?>) typeArgs[1]);
                            }
                        }
                    }
                }

                // 尝试从父类中获取
                Type genericSuperclass = loaderClass.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    Type[] typeArgs = paramType.getActualTypeArguments();
                    if (typeArgs.length >= 2) {
                        return new TypeInfo((Class<?>) typeArgs[0], (Class<?>) typeArgs[1]);
                    }
                }
            } catch (Exception e) {
                logger.debug("提取CacheLoader泛型类型时出错: {}", e.getMessage());
            }
            return null;
        }

        /**
         * 检查两个类型信息是否匹配
         */
        private boolean typesMatch(TypeInfo cacheType, TypeInfo loaderType) {
            return cacheType.keyType.equals(loaderType.keyType) &&
                    cacheType.valueType.equals(loaderType.valueType);
        }

        /**
         * 将CacheLoader注入到Cache中
         * 现在非常简单：直接调用Cache.setLoader()方法
         */
        @SuppressWarnings("unchecked")
        private void injectLoaderToCache(Cache<Object, Object> cache, CacheLoader<Object, Object> loader,
                                         String cacheBeanName, String loaderBeanName) {
            try {
                // 直接使用Cache接口的setLoader方法
                cache.setLoader(loader);

                logger.info("成功为Cache {} 设置CacheLoader {}，类型匹配: {}<->{}",
                        cacheBeanName, loaderBeanName, "K", "V");

            } catch (Exception e) {
                logger.error("注入CacheLoader {} 到Cache {} 时失败: {}",
                        loaderBeanName, cacheBeanName, e.getMessage());
            }
        }

        /**
         * 类型信息包装类
         */
        private static class TypeInfo {
            final Class<?> keyType;
            final Class<?> valueType;

            TypeInfo(Class<?> keyType, Class<?> valueType) {
                this.keyType = keyType;
                this.valueType = valueType;
            }

            @Override
            public String toString() {
                return String.format("TypeInfo{keyType=%s, valueType=%s}",
                        keyType.getSimpleName(), valueType.getSimpleName());
            }
        }
    }
}