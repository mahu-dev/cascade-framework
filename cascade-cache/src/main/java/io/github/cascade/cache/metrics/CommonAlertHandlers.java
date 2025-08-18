package io.github.cascade.cache.metrics;

import io.github.cascade.cache.metrics.CacheMonitoringManager.Alert;
import io.github.cascade.cache.metrics.CacheMonitoringManager.AlertHandler;
import io.github.cascade.cache.metrics.CacheMonitoringManager.AlertSeverity;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 常用告警处理器
 * 提供一些预定义的告警处理实现
 *
 * @author Cascade Framework
 */
public class CommonAlertHandlers {

    /**
     * 日志告警处理器
     * 将告警信息输出到日志
     */
    public static class LoggingAlertHandler implements AlertHandler {
        private final Logger logger;
        private final Map<AlertSeverity, Level> severityLevelMap;

        public LoggingAlertHandler() {
            this(Logger.getLogger(LoggingAlertHandler.class.getName()));
        }

        public LoggingAlertHandler(Logger logger) {
            this.logger = logger;
            this.severityLevelMap = new ConcurrentHashMap<>();
            this.severityLevelMap.put(AlertSeverity.LOW, Level.INFO);
            this.severityLevelMap.put(AlertSeverity.MEDIUM, Level.WARNING);
            this.severityLevelMap.put(AlertSeverity.HIGH, Level.SEVERE);
            this.severityLevelMap.put(AlertSeverity.CRITICAL, Level.SEVERE);
        }

        @Override
        public void handleAlert(Alert alert) {
            Level level = severityLevelMap.getOrDefault(alert.getSeverity(), Level.WARNING);
            String message = String.format("[%s] Cache Alert - %s: %s (Cache: %s, Rule: %s)",
                alert.getSeverity(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                alert.getMessage(),
                alert.getCacheName(),
                alert.getRuleName());
            
            logger.log(level, message);
        }

        public LoggingAlertHandler setSeverityLevel(AlertSeverity severity, Level level) {
            severityLevelMap.put(severity, level);
            return this;
        }
    }

    /**
     * 控制台告警处理器
     * 将告警信息输出到控制台
     */
    public static class ConsoleAlertHandler implements AlertHandler {
        private final boolean useColors;
        private final Map<AlertSeverity, String> colorMap;

        public ConsoleAlertHandler() {
            this(true);
        }

        public ConsoleAlertHandler(boolean useColors) {
            this.useColors = useColors;
            this.colorMap = new ConcurrentHashMap<>();
            if (useColors) {
                this.colorMap.put(AlertSeverity.LOW, "\u001B[32m");      // Green
                this.colorMap.put(AlertSeverity.MEDIUM, "\u001B[33m");   // Yellow
                this.colorMap.put(AlertSeverity.HIGH, "\u001B[31m");     // Red
                this.colorMap.put(AlertSeverity.CRITICAL, "\u001B[35m"); // Magenta
            }
        }

        @Override
        public void handleAlert(Alert alert) {
            String color = useColors ? colorMap.getOrDefault(alert.getSeverity(), "") : "";
            String reset = useColors ? "\u001B[0m" : "";
            
            String message = String.format("%s[%s ALERT] %s - %s: %s (Cache: %s)%s",
                color,
                alert.getSeverity(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME),
                alert.getRuleName(),
                alert.getMessage(),
                alert.getCacheName(),
                reset);
            
            System.out.println(message);
        }
    }

    /**
     * 回调告警处理器
     * 使用自定义回调函数处理告警
     */
    public static class CallbackAlertHandler implements AlertHandler {
        private final Consumer<Alert> callback;

        public CallbackAlertHandler(Consumer<Alert> callback) {
            this.callback = callback;
        }

        @Override
        public void handleAlert(Alert alert) {
            try {
                callback.accept(alert);
            } catch (Exception e) {
                // 记录异常但不抛出，避免影响其他处理器
                System.err.println("Error in alert callback: " + e.getMessage());
            }
        }
    }

    /**
     * 队列告警处理器
     * 将告警放入队列中，供其他线程异步处理
     */
    public static class QueueAlertHandler implements AlertHandler {
        private final BlockingQueue<Alert> alertQueue;
        private final int maxQueueSize;
        private final AtomicLong droppedAlerts;

        public QueueAlertHandler() {
            this(1000);
        }

        public QueueAlertHandler(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            this.alertQueue = new LinkedBlockingQueue<>(maxQueueSize);
            this.droppedAlerts = new AtomicLong(0);
        }

        @Override
        public void handleAlert(Alert alert) {
            if (!alertQueue.offer(alert)) {
                droppedAlerts.incrementAndGet();
            }
        }

        /**
         * 获取下一个告警（阻塞）
         */
        public Alert takeAlert() throws InterruptedException {
            return alertQueue.take();
        }

        /**
         * 获取下一个告警（非阻塞）
         */
        public Alert pollAlert() {
            return alertQueue.poll();
        }

        /**
         * 获取队列大小
         */
        public int getQueueSize() {
            return alertQueue.size();
        }

        /**
         * 获取丢弃的告警数量
         */
        public long getDroppedAlertsCount() {
            return droppedAlerts.get();
        }

        /**
         * 清空队列
         */
        public void clear() {
            alertQueue.clear();
        }
    }

    /**
     * 过滤告警处理器
     * 根据条件过滤告警
     */
    public static class FilteringAlertHandler implements AlertHandler {
        private final AlertHandler delegate;
        private final AlertFilter filter;

        public FilteringAlertHandler(AlertHandler delegate, AlertFilter filter) {
            this.delegate = delegate;
            this.filter = filter;
        }

        @Override
        public void handleAlert(Alert alert) {
            if (filter.shouldHandle(alert)) {
                delegate.handleAlert(alert);
            }
        }

        @FunctionalInterface
        public interface AlertFilter {
            boolean shouldHandle(Alert alert);
        }

        /**
         * 创建严重程度过滤器
         */
        public static AlertFilter severityFilter(AlertSeverity minSeverity) {
            return alert -> alert.getSeverity().ordinal() >= minSeverity.ordinal();
        }

        /**
         * 创建缓存名称过滤器
         */
        public static AlertFilter cacheNameFilter(Set<String> allowedCacheNames) {
            return alert -> allowedCacheNames.contains(alert.getCacheName());
        }

        /**
         * 创建规则名称过滤器
         */
        public static AlertFilter ruleNameFilter(Set<String> allowedRuleNames) {
            return alert -> allowedRuleNames.contains(alert.getRuleName());
        }
    }

    /**
     * 限流告警处理器
     * 限制告警处理的频率
     */
    public static class RateLimitingAlertHandler implements AlertHandler {
        private final AlertHandler delegate;
        private final long intervalMs;
        private final Map<String, Long> lastAlertTimes;

        public RateLimitingAlertHandler(AlertHandler delegate, long intervalMs) {
            this.delegate = delegate;
            this.intervalMs = intervalMs;
            this.lastAlertTimes = new ConcurrentHashMap<>();
        }

        @Override
        public void handleAlert(Alert alert) {
            String key = alert.getCacheName() + ":" + alert.getRuleName();
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastAlertTimes.get(key);
            
            if (lastTime == null || (currentTime - lastTime) >= intervalMs) {
                lastAlertTimes.put(key, currentTime);
                delegate.handleAlert(alert);
            }
        }

        /**
         * 清除限流状态
         */
        public void clearRateLimit() {
            lastAlertTimes.clear();
        }

        /**
         * 清除指定缓存的限流状态
         */
        public void clearRateLimit(String cacheName) {
            lastAlertTimes.entrySet().removeIf(entry -> entry.getKey().startsWith(cacheName + ":"));
        }
    }

    /**
     * 组合告警处理器
     * 将多个处理器组合在一起
     */
    public static class CompositeAlertHandler implements AlertHandler {
        private final Set<AlertHandler> handlers;

        public CompositeAlertHandler() {
            this.handlers = new CopyOnWriteArraySet<>();
        }

        public CompositeAlertHandler(AlertHandler... handlers) {
            this();
            for (AlertHandler handler : handlers) {
                this.handlers.add(handler);
            }
        }

        @Override
        public void handleAlert(Alert alert) {
            for (AlertHandler handler : handlers) {
                try {
                    handler.handleAlert(alert);
                } catch (Exception e) {
                    // 记录异常但继续处理其他处理器
                    System.err.println("Error in alert handler: " + e.getMessage());
                }
            }
        }

        /**
         * 添加处理器
         */
        public CompositeAlertHandler addHandler(AlertHandler handler) {
            handlers.add(handler);
            return this;
        }

        /**
         * 移除处理器
         */
        public CompositeAlertHandler removeHandler(AlertHandler handler) {
            handlers.remove(handler);
            return this;
        }

        /**
         * 获取处理器数量
         */
        public int getHandlerCount() {
            return handlers.size();
        }
    }

    /**
     * 创建默认的告警处理器组合
     */
    public static AlertHandler createDefaultHandler() {
        return new CompositeAlertHandler(
            new LoggingAlertHandler(),
            new FilteringAlertHandler(
                new ConsoleAlertHandler(),
                FilteringAlertHandler.severityFilter(AlertSeverity.MEDIUM)
            )
        );
    }

    /**
     * 创建生产环境的告警处理器组合
     */
    public static AlertHandler createProductionHandler() {
        return new CompositeAlertHandler(
            new RateLimitingAlertHandler(
                new LoggingAlertHandler(),
                60000 // 1分钟限流
            ),
            new FilteringAlertHandler(
                new ConsoleAlertHandler(false), // 不使用颜色
                FilteringAlertHandler.severityFilter(AlertSeverity.HIGH)
            )
        );
    }

    /**
     * 创建开发环境的告警处理器组合
     */
    public static AlertHandler createDevelopmentHandler() {
        return new CompositeAlertHandler(
            new LoggingAlertHandler(),
            new ConsoleAlertHandler(true) // 使用颜色
        );
    }
}