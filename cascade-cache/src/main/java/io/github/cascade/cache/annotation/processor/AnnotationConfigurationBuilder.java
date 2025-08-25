package io.github.cascade.cache.annotation.processor;

import io.github.cascade.cache.annotation.CascadeCacheable;
import io.github.cascade.cache.annotation.CascadeCacheRefresh;
import io.github.cascade.cache.config.CascadeCacheConfiguration;
import io.github.cascade.cache.protection.CascadeBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 注解配置构建器
 * 将注解属性转换为完整的CascadeCacheConfiguration对象
 * 
 * @author cascade
 */
@Slf4j
public class AnnotationConfigurationBuilder {
    
    private final ApplicationContext applicationContext;
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public AnnotationConfigurationBuilder(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    /**
     * 从@CascadeCacheable注解构建配置
     */
    public CascadeCacheConfiguration buildConfiguration(CascadeCacheable annotation, 
                                                      Method method, Object[] args) {
        String cacheName = getCacheName(annotation);
        CascadeCacheConfiguration config = new CascadeCacheConfiguration().setName(cacheName);
        
        // 构建通用配置
        buildCommonConfig(config, annotation, method, args);
        
        // 构建L1配置
        buildL1Config(config, annotation, method, args);
        
        // 构建L2配置
        buildL2Config(config, annotation, method, args);
        
        // 构建同步配置
        buildSyncConfig(config, annotation, method, args);
        
        // 构建防护配置
        buildProtectionConfig(config, annotation, method, args);
        
        // 构建监控配置
        buildMonitoringConfig(config, annotation, method, args);
        
        // 构建刷新配置
        buildRefreshConfig(config, annotation, method, args);
        
        log.debug("Built configuration for cache '{}' from annotation", cacheName);
        return config;
    }
    
    /**
     * 从@CascadeCacheRefresh注解构建刷新配置
     */
    public CascadeCacheConfiguration.RefreshConfig buildRefreshConfiguration(CascadeCacheRefresh annotation,
                                                                           Method method, Object[] args) {
        CascadeCacheConfiguration.RefreshConfig refreshConfig = new CascadeCacheConfiguration.RefreshConfig();
        
        refreshConfig.setEnabled(true);
        
        // 刷新间隔
        if (StringUtils.hasText(annotation.refreshInterval())) {
            Duration interval = parseSpelDuration(annotation.refreshInterval(), method, args);
            refreshConfig.setDefaultRefreshInterval(interval);
        }
        
        // 最小刷新间隔
        if (StringUtils.hasText(annotation.minRefreshInterval())) {
            Duration minInterval = parseSpelDuration(annotation.minRefreshInterval(), method, args);
            refreshConfig.setMinRefreshInterval(minInterval);
        }
        
        // 最大刷新间隔
        if (StringUtils.hasText(annotation.maxRefreshInterval())) {
            Duration maxInterval = parseSpelDuration(annotation.maxRefreshInterval(), method, args);
            refreshConfig.setMaxRefreshInterval(maxInterval);
        }
        
        // 并发刷新
        refreshConfig.setAllowConcurrentRefresh(annotation.allowConcurrentRefresh());
        
        // 刷新超时
        if (StringUtils.hasText(annotation.refreshTimeout())) {
            Duration timeout = parseSpelDuration(annotation.refreshTimeout(), method, args);
            refreshConfig.setRefreshTimeout(timeout);
        }
        
        // 重试配置
        if (annotation.maxRetries() > 0) {
            refreshConfig.setMaxRetries(annotation.maxRetries());
        }
        
        if (StringUtils.hasText(annotation.retryInterval())) {
            Duration retryInterval = parseSpelDuration(annotation.retryInterval(), method, args);
            refreshConfig.setRetryInterval(retryInterval);
        }
        
        // 预加载配置
        CascadeCacheConfiguration.RefreshConfig.PreloadConfig preloadConfig = 
                new CascadeCacheConfiguration.RefreshConfig.PreloadConfig();
        preloadConfig.setEnabled(annotation.enablePreload());
        
        if (annotation.preloadBatchSize() > 0) {
            preloadConfig.setBatchSize(annotation.preloadBatchSize());
        }
        
        if (annotation.preloadConcurrency() > 0) {
            preloadConfig.setConcurrency(annotation.preloadConcurrency());
        }
        
        refreshConfig.setPreload(preloadConfig);
        refreshConfig.setStartOnInit(annotation.startOnInit());
        
        return refreshConfig;
    }
    
    /**
     * 构建通用配置
     */
    private void buildCommonConfig(CascadeCacheConfiguration config, CascadeCacheable annotation,
                                 Method method, Object[] args) {
        CascadeCacheConfiguration.CommonConfig commonConfig = config.getCommon();
        
        // TTL设置
        if (StringUtils.hasText(annotation.ttl())) {
            Duration ttl = parseSpelDuration(annotation.ttl(), method, args);
            commonConfig.setExpireAfterWrite(ttl);
        }
        
        // 统计设置
        commonConfig.setRecordStats(annotation.enableMetrics());
        
        // 自定义执行器
        if (StringUtils.hasText(annotation.executorBean())) {
            try {
                Executor executor = applicationContext.getBean(annotation.executorBean(), Executor.class);
                commonConfig.setExecutor(executor);
            } catch (Exception e) {
                log.warn("Failed to get executor bean '{}': {}", annotation.executorBean(), e.getMessage());
            }
        }
    }
    
    /**
     * 构建L1配置
     */
    private void buildL1Config(CascadeCacheConfiguration config, CascadeCacheable annotation,
                             Method method, Object[] args) {
        CascadeCacheConfiguration.L1Config l1Config = config.getL1();
        
        l1Config.setEnabled(annotation.enableL1());
        
        if (annotation.l1MaximumSize() > 0) {
            l1Config.setMaximumSize(annotation.l1MaximumSize());
        }
        
        if (annotation.l1InitialCapacity() > 0) {
            l1Config.setInitialCapacity(annotation.l1InitialCapacity());
        }
        
        if (annotation.l1ConcurrencyLevel() > 0) {
            l1Config.setConcurrencyLevel(annotation.l1ConcurrencyLevel());
        }
        
        if (StringUtils.hasText(annotation.l1ExpireAfterAccess())) {
            Duration duration = parseSpelDuration(annotation.l1ExpireAfterAccess(), method, args);
            l1Config.setExpireAfterAccess(duration);
        }
        
        if (StringUtils.hasText(annotation.l1ExpireAfterWrite())) {
            Duration duration = parseSpelDuration(annotation.l1ExpireAfterWrite(), method, args);
            l1Config.setExpireAfterWrite(duration);
        }
        
        l1Config.setRecordStats(annotation.enableMetrics());
    }
    
    /**
     * 构建L2配置
     */
    private void buildL2Config(CascadeCacheConfiguration config, CascadeCacheable annotation,
                             Method method, Object[] args) {
        CascadeCacheConfiguration.L2Config l2Config = config.getL2();
        
        l2Config.setEnabled(annotation.enableL2());
        
        if (StringUtils.hasText(annotation.l2KeyPrefix())) {
            String keyPrefix = parseSpelString(annotation.l2KeyPrefix(), method, args);
            l2Config.setKeyPrefix(keyPrefix);
        }
        
        if (StringUtils.hasText(annotation.l2DefaultTtl())) {
            Duration ttl = parseSpelDuration(annotation.l2DefaultTtl(), method, args);
            l2Config.setDefaultTtl(ttl);
        }
        
        if (StringUtils.hasText(annotation.l2Timeout())) {
            Duration timeout = parseSpelDuration(annotation.l2Timeout(), method, args);
            l2Config.setTimeout(timeout);
        }
        
        if (StringUtils.hasText(annotation.l2Serializer())) {
            String serializer = parseSpelString(annotation.l2Serializer(), method, args);
            l2Config.setSerializer(serializer);
        }
        
        l2Config.setEnableBatch(annotation.l2EnableBatch());
        
        if (annotation.l2BatchSize() > 0) {
            l2Config.setBatchSize(annotation.l2BatchSize());
        }
        
        // 自定义Redis客户端
        if (StringUtils.hasText(annotation.redisClientBean())) {
            try {
                RedissonClient redissonClient = applicationContext.getBean(annotation.redisClientBean(), RedissonClient.class);
                l2Config.setRedissonClient(redissonClient);
            } catch (Exception e) {
                log.warn("Failed to get redis client bean '{}': {}", annotation.redisClientBean(), e.getMessage());
            }
        }
    }
    
    /**
     * 构建同步配置
     */
    private void buildSyncConfig(CascadeCacheConfiguration config, CascadeCacheable annotation,
                               Method method, Object[] args) {
        CascadeCacheConfiguration.SyncConfig syncConfig = config.getSync();
        
        syncConfig.setEnabled(annotation.enableSync());
        
        if (StringUtils.hasText(annotation.syncTopic())) {
            String topic = parseSpelString(annotation.syncTopic(), method, args);
            syncConfig.setTopic(topic);
        }
        
        if (StringUtils.hasText(annotation.syncTimeout())) {
            Duration timeout = parseSpelDuration(annotation.syncTimeout(), method, args);
            syncConfig.setTimeout(timeout);
        }
        
        syncConfig.setAsync(annotation.asyncSync());
    }
    
    /**
     * 构建防护配置
     */
    private void buildProtectionConfig(CascadeCacheConfiguration config, CascadeCacheable annotation,
                                     Method method, Object[] args) {
        CascadeCacheConfiguration.ProtectionConfig protectionConfig = config.getProtection();
        
        boolean hasProtection = annotation.enableBloomFilter() || annotation.enableDistributedLock() || annotation.enableRandomTtl();
        protectionConfig.setEnabled(hasProtection);
        
        // 布隆过滤器配置
        CascadeCacheConfiguration.ProtectionConfig.BloomFilterConfig bloomConfig = protectionConfig.getBloomFilter();
        bloomConfig.setEnabled(annotation.enableBloomFilter());
        
        if (annotation.bloomExpectedElements() > 0) {
            bloomConfig.setExpectedElements(annotation.bloomExpectedElements());
        }
        
        if (annotation.bloomFalsePositiveRate() > 0) {
            bloomConfig.setFalsePositiveRate(annotation.bloomFalsePositiveRate());
        }
        
        if (StringUtils.hasText(annotation.bloomFilterBean())) {
            try {
                CascadeBloomFilter bloomFilter = applicationContext.getBean(annotation.bloomFilterBean(), CascadeBloomFilter.class);
                bloomConfig.setCustomFilter(bloomFilter);
            } catch (Exception e) {
                log.warn("Failed to get bloom filter bean '{}': {}", annotation.bloomFilterBean(), e.getMessage());
            }
        }
        
        // 分布式锁配置
        CascadeCacheConfiguration.ProtectionConfig.DistributedLockConfig lockConfig = protectionConfig.getDistributedLock();
        lockConfig.setEnabled(annotation.enableDistributedLock());
        
        if (StringUtils.hasText(annotation.lockTimeout())) {
            Duration timeout = parseSpelDuration(annotation.lockTimeout(), method, args);
            lockConfig.setLockTimeout(timeout);
        }
        
        if (StringUtils.hasText(annotation.lockWaitTimeout())) {
            Duration waitTimeout = parseSpelDuration(annotation.lockWaitTimeout(), method, args);
            lockConfig.setWaitTimeout(waitTimeout);
        }
        
        if (annotation.lockMaxRetries() > 0) {
            lockConfig.setMaxRetries(annotation.lockMaxRetries());
        }
        
        if (StringUtils.hasText(annotation.lockRetryDelay())) {
            Duration retryDelay = parseSpelDuration(annotation.lockRetryDelay(), method, args);
            lockConfig.setRetryDelay(retryDelay);
        }
        
        if (StringUtils.hasText(annotation.lockKeyPrefix())) {
            String keyPrefix = parseSpelString(annotation.lockKeyPrefix(), method, args);
            lockConfig.setKeyPrefix(keyPrefix);
        }
        
        // 随机TTL配置
        CascadeCacheConfiguration.ProtectionConfig.RandomTtlConfig randomTtlConfig = protectionConfig.getRandomTtl();
        randomTtlConfig.setEnabled(annotation.enableRandomTtl());
        
        if (StringUtils.hasText(annotation.randomTtlBase())) {
            Duration baseTtl = parseSpelDuration(annotation.randomTtlBase(), method, args);
            randomTtlConfig.setBaseTtl(baseTtl);
        }
        
        if (StringUtils.hasText(annotation.randomTtlJitterRange())) {
            Duration jitterRange = parseSpelDuration(annotation.randomTtlJitterRange(), method, args);
            randomTtlConfig.setJitterRange(jitterRange);
        }
        
        if (annotation.randomTtlJitterRatio() > 0) {
            randomTtlConfig.setJitterRatio(annotation.randomTtlJitterRatio());
        }
    }
    
    /**
     * 构建监控配置
     */
    private void buildMonitoringConfig(CascadeCacheConfiguration config, CascadeCacheable annotation,
                                     Method method, Object[] args) {
        CascadeCacheConfiguration.MonitoringConfig monitoringConfig = config.getMonitoring();
        
        monitoringConfig.setEnabled(annotation.enableMonitoring());
        monitoringConfig.setEnableMetrics(annotation.enableMetrics());
        monitoringConfig.setEnableTracing(annotation.enableTracing());
        
        if (StringUtils.hasText(annotation.metricsInterval())) {
            Duration interval = parseSpelDuration(annotation.metricsInterval(), method, args);
            monitoringConfig.setMetricsInterval(interval);
        }
        
        if (StringUtils.hasText(annotation.eventListenerBean())) {
            String listenerClass = parseSpelString(annotation.eventListenerBean(), method, args);
            monitoringConfig.setEventListenerClass(listenerClass);
        }
    }
    
    /**
     * 构建刷新配置
     */
    private void buildRefreshConfig(CascadeCacheConfiguration config, CascadeCacheable annotation,
                                  Method method, Object[] args) {
        CascadeCacheConfiguration.RefreshConfig refreshConfig = config.getRefresh();
        
        refreshConfig.setEnabled(annotation.enableAutoRefresh());
        
        if (StringUtils.hasText(annotation.refreshInterval())) {
            Duration interval = parseSpelDuration(annotation.refreshInterval(), method, args);
            refreshConfig.setDefaultRefreshInterval(interval);
        }
        
        if (StringUtils.hasText(annotation.minRefreshInterval())) {
            Duration minInterval = parseSpelDuration(annotation.minRefreshInterval(), method, args);
            refreshConfig.setMinRefreshInterval(minInterval);
        }
        
        if (StringUtils.hasText(annotation.maxRefreshInterval())) {
            Duration maxInterval = parseSpelDuration(annotation.maxRefreshInterval(), method, args);
            refreshConfig.setMaxRefreshInterval(maxInterval);
        }
        
        if (annotation.refreshThreadPoolSize() > 0) {
            refreshConfig.setThreadPoolSize(annotation.refreshThreadPoolSize());
        }
        
        if (annotation.refreshQueueCapacity() > 0) {
            refreshConfig.setQueueCapacity(annotation.refreshQueueCapacity());
        }
        
        refreshConfig.setAllowConcurrentRefresh(annotation.allowConcurrentRefresh());
        
        if (StringUtils.hasText(annotation.refreshTimeout())) {
            Duration timeout = parseSpelDuration(annotation.refreshTimeout(), method, args);
            refreshConfig.setRefreshTimeout(timeout);
        }
        
        if (annotation.refreshMaxRetries() > 0) {
            refreshConfig.setMaxRetries(annotation.refreshMaxRetries());
        }
        
        if (StringUtils.hasText(annotation.refreshRetryInterval())) {
            Duration retryInterval = parseSpelDuration(annotation.refreshRetryInterval(), method, args);
            refreshConfig.setRetryInterval(retryInterval);
        }
        
        // 预加载配置
        CascadeCacheConfiguration.RefreshConfig.PreloadConfig preloadConfig = refreshConfig.getPreload();
        preloadConfig.setEnabled(annotation.enablePreload());
        
        if (annotation.preloadBatchSize() > 0) {
            preloadConfig.setBatchSize(annotation.preloadBatchSize());
        }
        
        if (annotation.preloadConcurrency() > 0) {
            preloadConfig.setConcurrency(annotation.preloadConcurrency());
        }
    }
    
    /**
     * 获取缓存名称
     */
    private String getCacheName(CascadeCacheable annotation) {
        String[] value = annotation.value();
        if (value.length > 0 && StringUtils.hasText(value[0])) {
            return value[0];
        }
        
        String[] cacheNames = annotation.cacheNames();
        if (cacheNames.length > 0 && StringUtils.hasText(cacheNames[0])) {
            return cacheNames[0];
        }
        
        return "default";
    }
    
    /**
     * 解析SpEL表达式获取Duration值
     */
    private Duration parseSpelDuration(String expression, Method method, Object[] args) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        
        try {
            // 尝试直接解析为Duration
            return Duration.parse(expression);
        } catch (Exception e) {
            // 尝试SpEL表达式解析
            try {
                EvaluationContext context = createEvaluationContext(method, args);
                Expression spelExpression = parser.parseExpression(expression);
                Object value = spelExpression.getValue(context);
                
                if (value instanceof Duration) {
                    return (Duration) value;
                } else if (value instanceof String) {
                    return Duration.parse((String) value);
                } else if (value instanceof Number) {
                    return Duration.ofSeconds(((Number) value).longValue());
                }
            } catch (Exception spelException) {
                log.warn("Failed to parse duration expression '{}': {}", expression, spelException.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * 解析SpEL表达式获取字符串值
     */
    private String parseSpelString(String expression, Method method, Object[] args) {
        if (!StringUtils.hasText(expression)) {
            return expression;
        }
        
        try {
            EvaluationContext context = createEvaluationContext(method, args);
            Expression spelExpression = parser.parseExpression(expression);
            Object value = spelExpression.getValue(context);
            return value != null ? value.toString() : expression;
        } catch (Exception e) {
            log.debug("Failed to parse string expression '{}': {}", expression, e.getMessage());
            return expression;
        }
    }
    
    /**
     * 创建SpEL评估上下文
     */
    private EvaluationContext createEvaluationContext(Method method, Object[] args) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        // 设置方法参数
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable("p" + i, args[i]);
                context.setVariable("a" + i, args[i]);
            }
        }
        
        // 设置方法信息
        context.setVariable("method", method);
        context.setVariable("target", method.getDeclaringClass());
        
        return context;
    }
}