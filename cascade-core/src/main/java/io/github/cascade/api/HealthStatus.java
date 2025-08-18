package io.github.cascade.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康状态
 */
public class HealthStatus {
    
    public enum Status {
        UP, DOWN, OUT_OF_SERVICE, UNKNOWN
    }
    
    private final Status status;
    private final Map<String, Object> details;
    private final Instant timestamp;
    
    public HealthStatus(Status status) {
        this(status, new HashMap<>());
    }
    
    public HealthStatus(Status status, Map<String, Object> details) {
        this.status = status;
        this.details = new HashMap<>(details);
        this.timestamp = Instant.now();
    }
    
    public Status getStatus() {
        return status;
    }
    
    public Map<String, Object> getDetails() {
        return new HashMap<>(details);
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static HealthStatus up() {
        return new HealthStatus(Status.UP);
    }
    
    public static HealthStatus down() {
        return new HealthStatus(Status.DOWN);
    }
    
    public static HealthStatus unknown() {
        return new HealthStatus(Status.UNKNOWN);
    }
    
    public static class Builder {
        private Status status;
        private final Map<String, Object> details = new HashMap<>();
        
        public Builder up() {
            this.status = Status.UP;
            return this;
        }
        
        public Builder down() {
            this.status = Status.DOWN;
            return this;
        }
        
        public Builder unknown() {
            this.status = Status.UNKNOWN;
            return this;
        }
        
        public Builder withDetail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }
        
        public Builder withComponent(String name, HealthStatus componentHealth) {
            this.details.put(name, componentHealth);
            return this;
        }
        
        public HealthStatus build() {
            return new HealthStatus(status != null ? status : Status.UNKNOWN, details);
        }
    }
}