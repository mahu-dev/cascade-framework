package io.github.cascade.config;

import io.github.cascade.foundation.CascadeConstants;

/**
 * Cascade全局配置
 */
public class CascadeConfig {
    
    private String namespace = CascadeConstants.DEFAULT_NAMESPACE;
    private String serializer = CascadeConstants.DEFAULT_SERIALIZER;
    private String codec = CascadeConstants.DEFAULT_CODEC;
    private EventConfig event = new EventConfig();
    private MetricsConfig metrics = new MetricsConfig();
    
    public String getNamespace() {
        return namespace;
    }
    
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
    
    public String getSerializer() {
        return serializer;
    }
    
    public void setSerializer(String serializer) {
        this.serializer = serializer;
    }
    
    public String getCodec() {
        return codec;
    }
    
    public void setCodec(String codec) {
        this.codec = codec;
    }
    
    public EventConfig getEvent() {
        return event;
    }
    
    public void setEvent(EventConfig event) {
        this.event = event;
    }
    
    public MetricsConfig getMetrics() {
        return metrics;
    }
    
    public void setMetrics(MetricsConfig metrics) {
        this.metrics = metrics;
    }
    
    /**
     * 事件配置
     */
    public static class EventConfig {
        private boolean enabled = true;
        private boolean async = true;
        private int threadPoolSize = 10;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isAsync() {
            return async;
        }
        
        public void setAsync(boolean async) {
            this.async = async;
        }
        
        public int getThreadPoolSize() {
            return threadPoolSize;
        }
        
        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }
    
    /**
     * 监控配置
     */
    public static class MetricsConfig {
        private boolean enabled = true;
        private String exportInterval = "60s";
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getExportInterval() {
            return exportInterval;
        }
        
        public void setExportInterval(String exportInterval) {
            this.exportInterval = exportInterval;
        }
    }
}