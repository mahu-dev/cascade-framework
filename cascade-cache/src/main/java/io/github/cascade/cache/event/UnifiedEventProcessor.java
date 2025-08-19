package io.github.cascade.cache.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 统一事件处理器
 * 负责事件的分发和处理
 *
 * @author Cascade Framework
 */
public class UnifiedEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEventProcessor.class);

    private final List<EventHandler> handlers = new CopyOnWriteArrayList<>();
    private final ExecutorService asyncExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean enableAsyncProcessing;

    public UnifiedEventProcessor() {
        this(true);
    }

    public UnifiedEventProcessor(boolean enableAsyncProcessing) {
        this.enableAsyncProcessing = enableAsyncProcessing;
        this.asyncExecutor = enableAsyncProcessing ? 
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "unified-event-processor");
                t.setDaemon(true);
                return t;
            }) : null;
    }

    /**
     * 启动事件处理器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Unified event processor started with {} handlers", handlers.size());
        }
    }

    /**
     * 停止事件处理器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
            }
            log.info("Unified event processor stopped");
        }
    }

    /**
     * 发布事件
     */
    public void publishEvent(UnifiedCacheEvent event) {
        if (!running.get()) {
            log.debug("Event processor not running, ignoring event: {}", event);
            return;
        }

        if (enableAsyncProcessing) {
            asyncExecutor.execute(() -> processEvent(event));
        } else {
            processEvent(event);
        }
    }

    /**
     * 注册事件处理器
     */
    public void registerHandler(EventHandler handler) {
        handlers.add(handler);
        log.debug("Registered event handler: {}", handler.getClass().getSimpleName());
    }

    /**
     * 注销事件处理器
     */
    public void unregisterHandler(EventHandler handler) {
        if (handlers.remove(handler)) {
            log.debug("Unregistered event handler: {}", handler.getClass().getSimpleName());
        }
    }

    /**
     * 处理事件
     */
    private void processEvent(UnifiedCacheEvent event) {
        for (EventHandler handler : handlers) {
            if (handler.canHandle(event)) {
                try {
                    handler.handleEvent(event);
                } catch (Exception e) {
                    log.error("Error in event handler {}: {}", 
                        handler.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 获取处理器数量
     */
    public int getHandlerCount() {
        return handlers.size();
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 事件处理器接口
     */
    public interface EventHandler {
        
        /**
         * 是否可以处理此事件
         */
        boolean canHandle(UnifiedCacheEvent event);
        
        /**
         * 处理事件
         */
        void handleEvent(UnifiedCacheEvent event);
        
        /**
         * 获取处理器名称
         */
        default String getName() {
            return getClass().getSimpleName();
        }
    }

    /**
     * 抽象事件处理器基类
     */
    public abstract static class AbstractEventHandler implements EventHandler {
        
        private final Predicate<UnifiedCacheEvent> filter;
        
        protected AbstractEventHandler() {
            this.filter = null;
        }
        
        protected AbstractEventHandler(Predicate<UnifiedCacheEvent> filter) {
            this.filter = filter;
        }
        
        @Override
        public boolean canHandle(UnifiedCacheEvent event) {
            return filter == null || filter.test(event);
        }
    }

    /**
     * 日志事件处理器
     */
    public static class LoggingEventHandler extends AbstractEventHandler {
        
        private static final Logger eventLog = LoggerFactory.getLogger("cache.events");
        
        public LoggingEventHandler() {
            super();
        }
        
        public LoggingEventHandler(Predicate<UnifiedCacheEvent> filter) {
            super(filter);
        }
        
        @Override
        public void handleEvent(UnifiedCacheEvent event) {
            switch (event.getLevel()) {
                case TRACE:
                    if (eventLog.isTraceEnabled()) {
                        eventLog.trace("Cache event: {}", event);
                    }
                    break;
                case DEBUG:
                    if (eventLog.isDebugEnabled()) {
                        eventLog.debug("Cache event: {}", event);
                    }
                    break;
                case INFO:
                    eventLog.info("Cache event: {}", event);
                    break;
                case WARN:
                    eventLog.warn("Cache event: {}", event);
                    break;
                case ERROR:
                    if (event.getException() != null) {
                        eventLog.error("Cache event: {}", event, event.getException());
                    } else {
                        eventLog.error("Cache event: {}", event);
                    }
                    break;
            }
        }
    }

    /**
     * 指标收集事件处理器
     */
    public static class MetricsEventHandler extends AbstractEventHandler {
        
        public MetricsEventHandler() {
            // 只处理监控相关事件
            super(event -> event.isMonitoringEvent() || event.isOperationEvent());
        }
        
        @Override
        public void handleEvent(UnifiedCacheEvent event) {
            // 这里可以集成到统一的指标收集系统
            // 例如更新 CacheMetricsCollector
            log.debug("Collecting metrics for event: {}", event.getType());
        }
    }

    /**
     * 同步事件处理器
     */
    public static class SyncEventHandler extends AbstractEventHandler {
        
        public SyncEventHandler() {
            // 只处理同步事件
            super(UnifiedCacheEvent::isSyncEvent);
        }
        
        @Override
        public void handleEvent(UnifiedCacheEvent event) {
            // 处理分布式同步逻辑
            log.debug("Processing sync event: {} from node: {}", 
                event.getType(), event.getSourceNodeId());
        }
    }
}