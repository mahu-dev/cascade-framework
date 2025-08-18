package io.github.cascade.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Cascade事件基类
 */
public abstract class CascadeEvent {
    
    private final String source;
    private final Instant timestamp;
    private final Map<String, Object> metadata;
    
    protected CascadeEvent(String source) {
        this.source = source;
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>();
    }
    
    protected CascadeEvent(String source, Map<String, Object> metadata) {
        this.source = source;
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>(metadata);
    }
    
    public String getSource() {
        return source;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * 获取事件类型
     */
    public abstract String getEventType();
    
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "source='" + source + '\'' +
                ", timestamp=" + timestamp +
                ", type='" + getEventType() + '\'' +
                '}';
    }
}