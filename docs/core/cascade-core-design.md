# Cascade Core 模块设计文档

## 一、模块概述

`cascade-core` 是整个框架的核心基础模块，提供统一的门面入口、基础抽象、公共组件和扩展机制。

## 二、包结构

```
cascade-core/
└── src/main/java/io/github/cascade/
    ├── Cascade.java                      # 统一门面
    ├── CascadeContext.java               # 全局上下文
    ├── CascadeRegistry.java              # 组件注册中心
    ├── CascadeFactory.java               # 工厂类
    │
    ├── api/                              # 核心API
    │   ├── CascadeComponent.java         # 组件基础接口
    │   ├── Lifecycle.java                # 生命周期接口
    │   ├── Identifiable.java             # 可标识接口
    │   ├── Configurable.java             # 可配置接口
    │   └── Observable.java               # 可观测接口
    │
    ├── foundation/                       # 基础设施
    │   ├── AbstractCascadeComponent.java # 组件基类
    │   ├── ComponentType.java            # 组件类型枚举
    │   ├── CascadeConstants.java         # 常量定义
    │   └── NamespaceAware.java           # 命名空间感知
    │
    ├── config/                           # 配置相关
    │   ├── CascadeConfig.java            # 全局配置
    │   ├── RedissonConfig.java           # Redisson配置
    │   ├── ConfigSource.java             # 配置源接口
    │   └── DynamicConfig.java            # 动态配置
    │
    ├── serializer/                       # 序列化
    │   ├── Serializer.java               # 序列化接口
    │   ├── SerializerFactory.java        # 序列化工厂
    │   ├── impl/
    │   │   ├── JsonSerializer.java       # JSON序列化
    │   │   ├── KryoSerializer.java       # Kryo序列化
    │   │   ├── ProtobufSerializer.java   # Protobuf序列化
    │   │   └── JdkSerializer.java        # JDK序列化
    │   └── SerializerRegistry.java       # 序列化器注册表
    │
    ├── codec/                            # 编解码器
    │   ├── Codec.java                    # 编解码接口
    │   ├── CodecFactory.java             # 编解码工厂
    │   └── impl/
    │       ├── StringCodec.java
    │       ├── ByteArrayCodec.java
    │       └── CompositeCodec.java
    │
    ├── metrics/                          # 监控指标
    │   ├── MetricsCollector.java         # 指标收集器
    │   ├── MetricsRegistry.java          # 指标注册表
    │   ├── Metric.java                   # 指标接口
    │   ├── MetricType.java               # 指标类型
    │   └── impl/
    │       ├── CounterMetric.java        # 计数器
    │       ├── GaugeMetric.java          # 仪表盘
    │       ├── HistogramMetric.java      # 直方图
    │       └── TimerMetric.java          # 计时器
    │
    ├── event/                            # 事件机制
    │   ├── CascadeEvent.java             # 事件基类
    │   ├── EventBus.java                 # 事件总线
    │   ├── EventListener.java            # 事件监听器
    │   ├── EventPublisher.java           # 事件发布器
    │   └── events/
    │       ├── ComponentEvent.java       # 组件事件
    │       ├── ConfigChangeEvent.java    # 配置变更事件
    │       └── HealthCheckEvent.java     # 健康检查事件
    │
    ├── exception/                        # 异常体系
    │   ├── CascadeException.java         # 基础异常
    │   ├── ConfigurationException.java   # 配置异常
    │   ├── ComponentException.java       # 组件异常
    │   ├── SerializationException.java   # 序列化异常
    │   └── ErrorCode.java                # 错误码
    │
    ├── util/                            # 工具类
    │   ├── Assert.java                   # 断言工具
    │   ├── StringUtils.java              # 字符串工具
    │   ├── CollectionUtils.java          # 集合工具
    │   ├── ReflectionUtils.java          # 反射工具
    │   └── ThreadUtils.java              # 线程工具
    │
    └── spi/                              # SPI扩展
        ├── ExtensionLoader.java          # 扩展加载器
        ├── Extension.java                # 扩展注解
        ├── Adaptive.java                 # 自适应注解
        └── SpiRegistry.java              # SPI注册表
```

## 三、核心接口定义

### 3.1 Cascade - 统一门面

```java
package io.github.cascade;

/**
 * Cascade框架统一入口门面
 * 提供所有功能模块的访问入口
 */
public class Cascade {
    
    private final CascadeContext context;
    private final CascadeRegistry registry;
    private final EventBus eventBus;
    private final MetricsCollector metricsCollector;
    
    // 缓存的组件实例
    private volatile CascadeComponent cacheComponent;
    private volatile CascadeComponent lockComponent;
    private volatile CascadeComponent bloomComponent;
    private volatile CascadeComponent limiterComponent;
    
    /**
     * 获取组件的通用方法
     */
    public <T extends CascadeComponent> T getComponent(Class<T> componentType) {
        return registry.getComponent(componentType)
            .orElseThrow(() -> new ComponentException(
                String.format("Component %s not found. Please check dependencies.", 
                    componentType.getSimpleName())
            ));
    }
    
    /**
     * 判断组件是否可用
     */
    public boolean hasComponent(Class<? extends CascadeComponent> componentType) {
        return registry.hasComponent(componentType);
    }
    
    /**
     * 获取全局上下文
     */
    public CascadeContext getContext() {
        return context;
    }
    
    /**
     * 获取 Redisson 客户端（高级用法）
     */
    public RedissonClient getRedissonClient() {
        return context.getRedissonClient();
    }
    
    /**
     * 全局配置
     */
    public CascadeConfig getConfig() {
        return context.getConfig();
    }
    
    /**
     * 健康检查
     */
    public HealthStatus health() {
        HealthStatus.Builder builder = HealthStatus.builder();
        registry.getAllComponents().forEach(component -> {
            builder.withComponent(component.getName(), component.health());
        });
        return builder.build();
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        eventBus.publish(new ShutdownEvent());
        registry.getAllComponents().forEach(CascadeComponent::close);
        context.close();
    }
    
    /**
     * 获取指标信息
     */
    public MetricsSnapshot getMetrics() {
        return metricsCollector.snapshot();
    }
}
```

### 3.2 CascadeContext - 全局上下文

```java
package io.github.cascade;

/**
 * Cascade全局上下文
 * 管理全局状态和共享资源
 */
public class CascadeContext implements Closeable {
    
    private final String namespace;
    private final RedissonClient redissonClient;
    private final CascadeConfig config;
    private final SerializerFactory serializerFactory;
    private final CodecFactory codecFactory;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * 获取命名空间
     */
    public String getNamespace() {
        return namespace;
    }
    
    /**
     * 获取带命名空间的键
     */
    public String getNamespacedKey(String key) {
        return StringUtils.isNotBlank(namespace) 
            ? namespace + ":" + key 
            : key;
    }
    
    /**
     * 获取默认序列化器
     */
    public Serializer getSerializer() {
        return serializerFactory.getDefault();
    }
    
    /**
     * 获取指定类型的序列化器
     */
    public Serializer getSerializer(String type) {
        return serializerFactory.get(type);
    }
    
    /**
     * 获取编解码器
     */
    public <T> Codec<T> getCodec(Class<T> type) {
        return codecFactory.getCodec(type);
    }
    
    /**
     * 发布事件
     */
    public void publishEvent(CascadeEvent event) {
        eventBus.publish(event);
    }
    
    /**
     * 订阅事件
     */
    public <E extends CascadeEvent> void subscribe(
            Class<E> eventType, 
            EventListener<E> listener) {
        eventBus.subscribe(eventType, listener);
    }
    
    /**
     * 调度任务
     */
    public ScheduledFuture<?> schedule(
            Runnable task, 
            long delay, 
            TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }
    
    /**
     * 周期性调度任务
     */
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable task, 
            long initialDelay, 
            long period, 
            TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdown();
            redissonClient.shutdown();
        }
    }
}
```

### 3.3 CascadeComponent - 组件基础接口

```java
package io.github.cascade.api;

/**
 * Cascade组件基础接口
 * 所有功能模块都需要实现此接口
 */
public interface CascadeComponent extends Lifecycle, Identifiable, Observable {
    
    /**
     * 获取组件类型
     */
    ComponentType getType();
    
    /**
     * 获取组件名称
     */
    String getName();
    
    /**
     * 获取组件版本
     */
    String getVersion();
    
    /**
     * 获取组件描述
     */
    String getDescription();
    
    /**
     * 健康检查
     */
    HealthStatus health();
    
    /**
     * 获取组件配置
     */
    <T> T getConfiguration(Class<T> configType);
    
    /**
     * 重新加载配置
     */
    void reload(Object config);
    
    /**
     * 获取组件指标
     */
    ComponentMetrics getMetrics();
}
```

### 3.4 生命周期接口

```java
package io.github.cascade.api;

/**
 * 组件生命周期管理
 */
public interface Lifecycle {
    
    enum State {
        NEW,
        INITIALIZING,
        INITIALIZED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }
    
    /**
     * 初始化
     */
    void initialize() throws Exception;
    
    /**
     * 启动
     */
    void start() throws Exception;
    
    /**
     * 停止
     */
    void stop() throws Exception;
    
    /**
     * 关闭
     */
    void close();
    
    /**
     * 获取当前状态
     */
    State getState();
    
    /**
     * 是否正在运行
     */
    default boolean isRunning() {
        return getState() == State.STARTED;
    }
}
```

### 3.5 序列化接口

```java
package io.github.cascade.serializer;

/**
 * 序列化器接口
 */
public interface Serializer {
    
    /**
     * 序列化
     */
    byte[] serialize(Object obj) throws SerializationException;
    
    /**
     * 反序列化
     */
    <T> T deserialize(byte[] bytes, Class<T> type) throws SerializationException;
    
    /**
     * 获取序列化器名称
     */
    String getName();
    
    /**
     * 是否支持该类型
     */
    boolean supports(Class<?> type);
}

/**
 * 序列化工厂
 */
public class SerializerFactory {
    
    private final Map<String, Serializer> serializers = new ConcurrentHashMap<>();
    private volatile Serializer defaultSerializer;
    
    /**
     * 注册序列化器
     */
    public void register(String name, Serializer serializer) {
        serializers.put(name, serializer);
    }
    
    /**
     * 获取序列化器
     */
    public Serializer get(String name) {
        Serializer serializer = serializers.get(name);
        if (serializer == null) {
            throw new SerializationException("Serializer not found: " + name);
        }
        return serializer;
    }
    
    /**
     * 设置默认序列化器
     */
    public void setDefault(String name) {
        this.defaultSerializer = get(name);
    }
    
    /**
     * 获取默认序列化器
     */
    public Serializer getDefault() {
        return defaultSerializer;
    }
}
```

### 3.6 事件机制

```java
package io.github.cascade.event;

/**
 * 事件基类
 */
public abstract class CascadeEvent {
    private final long timestamp;
    private final String source;
    private final Map<String, Object> metadata;
    
    public CascadeEvent(String source) {
        this.timestamp = System.currentTimeMillis();
        this.source = source;
        this.metadata = new HashMap<>();
    }
    
    // getters...
}

/**
 * 事件监听器
 */
@FunctionalInterface
public interface EventListener<E extends CascadeEvent> {
    void onEvent(E event);
}

/**
 * 事件总线
 */
public class EventBus {
    
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    
    /**
     * 发布事件
     */
    public void publish(CascadeEvent event) {
        List<EventListener<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners != null) {
            executor.execute(() -> {
                eventListeners.forEach(listener -> {
                    try {
                        ((EventListener<CascadeEvent>) listener).onEvent(event);
                    } catch (Exception e) {
                        log.error("Event listener error", e);
                    }
                });
            });
        }
    }
    
    /**
     * 订阅事件
     */
    public <E extends CascadeEvent> void subscribe(
            Class<E> eventType, 
            EventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                 .add(listener);
    }
    
    /**
     * 取消订阅
     */
    public <E extends CascadeEvent> void unsubscribe(
            Class<E> eventType, 
            EventListener<E> listener) {
        List<EventListener<?>> eventListeners = listeners.get(eventType);
        if (eventListeners != null) {
            eventListeners.remove(listener);
        }
    }
}
```

### 3.7 监控指标

```java
package io.github.cascade.metrics;

/**
 * 指标收集器
 */
public class MetricsCollector {
    
    private final MetricsRegistry registry;
    private final List<MetricsExporter> exporters;
    
    /**
     * 记录计数
     */
    public void recordCount(String name, long value, String... tags) {
        registry.counter(name, tags).increment(value);
    }
    
    /**
     * 记录耗时
     */
    public void recordTime(String name, long duration, TimeUnit unit, String... tags) {
        registry.timer(name, tags).record(duration, unit);
    }
    
    /**
     * 记录仪表值
     */
    public void recordGauge(String name, double value, String... tags) {
        registry.gauge(name, tags).set(value);
    }
    
    /**
     * 创建直方图
     */
    public Histogram histogram(String name, String... tags) {
        return registry.histogram(name, tags);
    }
    
    /**
     * 获取快照
     */
    public MetricsSnapshot snapshot() {
        return MetricsSnapshot.builder()
            .timestamp(System.currentTimeMillis())
            .counters(registry.getCounters())
            .gauges(registry.getGauges())
            .timers(registry.getTimers())
            .histograms(registry.getHistograms())
            .build();
    }
    
    /**
     * 导出指标
     */
    public void export() {
        MetricsSnapshot snapshot = snapshot();
        exporters.forEach(exporter -> exporter.export(snapshot));
    }
}

/**
 * 指标类型
 */
public enum MetricType {
    COUNTER,    // 计数器
    GAUGE,      // 仪表
    TIMER,      // 计时器
    HISTOGRAM,  // 直方图
    SUMMARY     // 摘要
}

/**
 * 组件指标
 */
public interface ComponentMetrics {
    String getComponentName();
    Map<String, Object> getMetrics();
    void reset();
}
```

### 3.8 SPI扩展机制

```java
package io.github.cascade.spi;

/**
 * 扩展加载器
 */
public class ExtensionLoader<T> {
    
    private static final String EXTENSION_DIRECTORY = "META-INF/cascade/";
    private final Class<T> type;
    private final Map<String, Class<?>> extensionClasses = new ConcurrentHashMap<>();
    private final Map<String, T> extensionInstances = new ConcurrentHashMap<>();
    
    /**
     * 获取扩展加载器
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type must be interface");
        }
        if (!type.isAnnotationPresent(SPI.class)) {
            throw new IllegalArgumentException("Extension type must be annotated with @SPI");
        }
        return new ExtensionLoader<>(type);
    }
    
    /**
     * 获取扩展实例
     */
    public T getExtension(String name) {
        return extensionInstances.computeIfAbsent(name, this::createExtension);
    }
    
    /**
     * 获取默认扩展
     */
    public T getDefaultExtension() {
        SPI spi = type.getAnnotation(SPI.class);
        String defaultName = spi.value();
        if (StringUtils.isBlank(defaultName)) {
            throw new IllegalStateException("Default extension name is not specified");
        }
        return getExtension(defaultName);
    }
    
    /**
     * 获取自适应扩展
     */
    public T getAdaptiveExtension() {
        // 动态生成自适应扩展类
        Class<?> adaptiveClass = createAdaptiveExtensionClass();
        return (T) instantiate(adaptiveClass);
    }
    
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClass(name);
        return (T) instantiate(clazz);
    }
    
    private Class<?> getExtensionClass(String name) {
        loadExtensionClasses();
        Class<?> clazz = extensionClasses.get(name);
        if (clazz == null) {
            throw new IllegalStateException("Extension not found: " + name);
        }
        return clazz;
    }
    
    private void loadExtensionClasses() {
        String fileName = EXTENSION_DIRECTORY + type.getName();
        try {
            ClassLoader classLoader = ExtensionLoader.class.getClassLoader();
            Enumeration<URL> urls = classLoader.getResources(fileName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                loadExtensionClasses(url);
            }
        } catch (IOException e) {
            throw new RuntimeException("Load extension classes error", e);
        }
    }
}

/**
 * SPI注解
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SPI {
    String value() default "";
}

/**
 * 扩展注解
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Extension {
    String value();
}

/**
 * 自适应注解
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Adaptive {
    String[] value() default {};
}
```

## 四、使用示例

### 4.1 基础使用

```java
@Component
public class Example {
    
    @Autowired
    private Cascade cascade;
    
    public void demo() {
        // 获取上下文
        CascadeContext context = cascade.getContext();
        
        // 使用序列化器
        Serializer serializer = context.getSerializer();
        byte[] bytes = serializer.serialize(myObject);
        
        // 发布事件
        context.publishEvent(new CustomEvent("test"));
        
        // 订阅事件
        context.subscribe(CustomEvent.class, event -> {
            System.out.println("Received: " + event);
        });
        
        // 调度任务
        context.scheduleAtFixedRate(() -> {
            System.out.println("Periodic task");
        }, 0, 1, TimeUnit.MINUTES);
    }
}
```

### 4.2 扩展实现

```java
// 自定义序列化器
@Extension("custom")
public class CustomSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) {
        // 自定义序列化逻辑
    }
    
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        // 自定义反序列化逻辑
    }
}

// 注册扩展
// META-INF/cascade/io.github.cascade.serializer.Serializer
custom=com.example.CustomSerializer
```

## 五、配置说明

```yaml
cascade:
  # 核心配置
  core:
    namespace: "myapp"              # 全局命名空间
    serializer: "kryo"              # 默认序列化器
    codec: "composite"              # 默认编解码器
    
  # Redisson配置
  redisson:
    mode: "cluster"                 # 模式：single/cluster/sentinel
    config-file: "redisson.yaml"    # 配置文件路径
    
  # 事件配置
  event:
    enabled: true
    async: true
    thread-pool-size: 10
    
  # 监控配置
  metrics:
    enabled: true
    export-interval: 60s
    exporters:
      - type: "prometheus"
        endpoint: "/metrics"
      - type: "jmx"
        domain: "cascade"
```