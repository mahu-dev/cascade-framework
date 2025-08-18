package io.github.cascade.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * 指标快照
 */
public class MetricsSnapshot {
    
    private final Instant timestamp;
    private final Map<String, Object> counters;
    private final Map<String, Object> gauges;
    private final Map<String, Object> timers;
    private final Map<String, Object> histograms;
    
    public MetricsSnapshot(Instant timestamp,
                          Map<String, Object> counters,
                          Map<String, Object> gauges,
                          Map<String, Object> timers,
                          Map<String, Object> histograms) {
        this.timestamp = timestamp;
        this.counters = counters;
        this.gauges = gauges;
        this.timers = timers;
        this.histograms = histograms;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public Map<String, Object> getCounters() {
        return counters;
    }
    
    public Map<String, Object> getGauges() {
        return gauges;
    }
    
    public Map<String, Object> getTimers() {
        return timers;
    }
    
    public Map<String, Object> getHistograms() {
        return histograms;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Instant timestamp;
        private Map<String, Object> counters;
        private Map<String, Object> gauges;
        private Map<String, Object> timers;
        private Map<String, Object> histograms;
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder counters(Map<String, Object> counters) {
            this.counters = counters;
            return this;
        }
        
        public Builder gauges(Map<String, Object> gauges) {
            this.gauges = gauges;
            return this;
        }
        
        public Builder timers(Map<String, Object> timers) {
            this.timers = timers;
            return this;
        }
        
        public Builder histograms(Map<String, Object> histograms) {
            this.histograms = histograms;
            return this;
        }
        
        public MetricsSnapshot build() {
            return new MetricsSnapshot(timestamp, counters, gauges, timers, histograms);
        }
    }
}