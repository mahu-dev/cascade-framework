package io.github.cascade.cache.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 缓存统计信息导出器
 * 支持多种格式的统计信息导出
 */
public class CacheStatsExporter {
    
    private static final Logger log = LoggerFactory.getLogger(CacheStatsExporter.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public CacheStatsExporter() {
        objectMapper.findAndRegisterModules();
    }
    
    /**
     * 导出为JSON格式
     */
    public String exportToJson(CacheStats stats) throws IOException {
        return exportToJson(Collections.singletonList(stats));
    }
    
    /**
     * 导出多个缓存统计为JSON格式
     */
    public String exportToJson(Collection<CacheStats> statsCollection) throws IOException {
        Map<String, Object> exportData = createExportData(statsCollection);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(exportData);
    }
    
    /**
     * 导出为CSV格式
     */
    public String exportToCsv(CacheStats stats) {
        return exportToCsv(Collections.singletonList(stats));
    }
    
    /**
     * 导出多个缓存统计为CSV格式
     */
    public String exportToCsv(Collection<CacheStats> statsCollection) {
        StringWriter writer = new StringWriter();
        
        // CSV 头部
        writer.append("Cache Name,Hit Count,Miss Count,Hit Rate,Load Count,Load Success Rate,")
              .append("Avg Load Time (ms),Max Load Time (ms),Current Size,Max Size,")
              .append("Eviction Count,Put Count,Throughput (req/s),Load Throughput (load/s),")
              .append("Eviction Rate (evict/s),Uptime (s)\n");
        
        // CSV 数据行
        for (CacheStats stats : statsCollection) {
            writer.append(String.format(
                "%s,%d,%d,%.4f,%d,%.4f,%.4f,%.4f,%d,%d,%d,%d,%.4f,%.4f,%.4f,%d\n",
                escapeCSV(stats.getCacheName()),
                stats.getHitCount(),
                stats.getMissCount(),
                stats.getHitRate(),
                stats.getLoadCount(),
                stats.getLoadSuccessRate(),
                stats.getAverageLoadTimeMillis(),
                stats.getMaxLoadTimeMillis(),
                stats.getCurrentSize(),
                stats.getMaxSize(),
                stats.getEvictionCount(),
                stats.getPutCount(),
                stats.getThroughput(),
                stats.getLoadThroughput(),
                stats.getEvictionRate(),
                stats.getUptime().getSeconds()
            ));
        }
        
        return writer.toString();
    }
    
    /**
     * 导出为Prometheus格式
     */
    public String exportToPrometheus(CacheStats stats) {
        return exportToPrometheus(Collections.singletonList(stats));
    }
    
    /**
     * 导出多个缓存统计为Prometheus格式
     */
    public String exportToPrometheus(Collection<CacheStats> statsCollection) {
        StringBuilder sb = new StringBuilder();
        String timestamp = String.valueOf(System.currentTimeMillis());
        
        // 添加帮助信息和类型声明
        sb.append("# HELP cascade_cache_requests_total Total number of cache requests\n");
        sb.append("# TYPE cascade_cache_requests_total counter\n");
        
        sb.append("# HELP cascade_cache_hits_total Total number of cache hits\n");
        sb.append("# TYPE cascade_cache_hits_total counter\n");
        
        sb.append("# HELP cascade_cache_misses_total Total number of cache misses\n");
        sb.append("# TYPE cascade_cache_misses_total counter\n");
        
        sb.append("# HELP cascade_cache_hit_rate Current cache hit rate\n");
        sb.append("# TYPE cascade_cache_hit_rate gauge\n");
        
        sb.append("# HELP cascade_cache_loads_total Total number of cache loads\n");
        sb.append("# TYPE cascade_cache_loads_total counter\n");
        
        sb.append("# HELP cascade_cache_load_time_seconds Cache load time statistics\n");
        sb.append("# TYPE cascade_cache_load_time_seconds summary\n");
        
        sb.append("# HELP cascade_cache_size Current cache size\n");
        sb.append("# TYPE cascade_cache_size gauge\n");
        
        sb.append("# HELP cascade_cache_evictions_total Total number of cache evictions\n");
        sb.append("# TYPE cascade_cache_evictions_total counter\n");
        
        sb.append("# HELP cascade_cache_puts_total Total number of cache puts\n");
        sb.append("# TYPE cascade_cache_puts_total counter\n");
        
        sb.append("# HELP cascade_cache_uptime_seconds Cache uptime in seconds\n");
        sb.append("# TYPE cascade_cache_uptime_seconds counter\n");
        
        // 添加指标数据
        for (CacheStats stats : statsCollection) {
            String cacheName = stats.getCacheName();
            String cacheLabel = String.format("cache=\"%s\"", escapeProm(cacheName));
            
            // 请求总数
            sb.append(String.format("cascade_cache_requests_total{%s} %d %s\n", 
                cacheLabel, stats.getRequestCount(), timestamp));
            
            // 命中总数
            sb.append(String.format("cascade_cache_hits_total{%s} %d %s\n", 
                cacheLabel, stats.getHitCount(), timestamp));
            
            // 未命中总数
            sb.append(String.format("cascade_cache_misses_total{%s} %d %s\n", 
                cacheLabel, stats.getMissCount(), timestamp));
            
            // 命中率
            sb.append(String.format("cascade_cache_hit_rate{%s} %.6f %s\n", 
                cacheLabel, stats.getHitRate(), timestamp));
            
            // 加载总数
            sb.append(String.format("cascade_cache_loads_total{%s} %d %s\n", 
                cacheLabel, stats.getLoadCount(), timestamp));
            
            // 加载时间统计
            sb.append(String.format("cascade_cache_load_time_seconds{%s,quantile=\"avg\"} %.6f %s\n", 
                cacheLabel, stats.getAverageLoadTimeMillis() / 1000.0, timestamp));
            sb.append(String.format("cascade_cache_load_time_seconds{%s,quantile=\"max\"} %.6f %s\n", 
                cacheLabel, stats.getMaxLoadTimeMillis() / 1000.0, timestamp));
            sb.append(String.format("cascade_cache_load_time_seconds{%s,quantile=\"min\"} %.6f %s\n", 
                cacheLabel, stats.getMinLoadTimeMillis() / 1000.0, timestamp));
            
            // 当前大小
            sb.append(String.format("cascade_cache_size{%s} %d %s\n", 
                cacheLabel, stats.getCurrentSize(), timestamp));
            
            // 驱逐总数
            sb.append(String.format("cascade_cache_evictions_total{%s} %d %s\n", 
                cacheLabel, stats.getEvictionCount(), timestamp));
            
            // 放入总数
            sb.append(String.format("cascade_cache_puts_total{%s} %d %s\n", 
                cacheLabel, stats.getPutCount(), timestamp));
            
            // 运行时间
            sb.append(String.format("cascade_cache_uptime_seconds{%s} %d %s\n", 
                cacheLabel, stats.getUptime().getSeconds(), timestamp));
        }
        
        return sb.toString();
    }
    
    /**
     * 导出为HTML格式
     */
    public String exportToHtml(CacheStats stats) {
        return exportToHtml(Collections.singletonList(stats));
    }
    
    /**
     * 导出多个缓存统计为HTML格式
     */
    public String exportToHtml(Collection<CacheStats> statsCollection) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<title>Cache Statistics Report</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; }\n");
        html.append("table { border-collapse: collapse; width: 100%; }\n");
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        html.append("th { background-color: #f2f2f2; }\n");
        html.append("tr:nth-child(even) { background-color: #f9f9f9; }\n");
        html.append(".metric { font-weight: bold; color: #333; }\n");
        html.append(".value { color: #666; }\n");
        html.append(".good { color: #28a745; }\n");
        html.append(".warning { color: #ffc107; }\n");
        html.append(".danger { color: #dc3545; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        
        html.append("<h1>Cache Statistics Report</h1>\n");
        html.append("<p>Generated on: ").append(
            DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("</p>\n");
        
        html.append("<table>\n");
        html.append("<thead>\n<tr>\n");
        html.append("<th>Cache Name</th>");
        html.append("<th>Hit Rate</th>");
        html.append("<th>Requests</th>");
        html.append("<th>Loads</th>");
        html.append("<th>Avg Load Time</th>");
        html.append("<th>Size</th>");
        html.append("<th>Evictions</th>");
        html.append("<th>Throughput</th>");
        html.append("<th>Uptime</th>");
        html.append("</tr>\n</thead>\n<tbody>\n");
        
        for (CacheStats stats : statsCollection) {
            html.append("<tr>\n");
            
            // Cache Name
            html.append("<td class=\"metric\">").append(escapeHtml(stats.getCacheName())).append("</td>");
            
            // Hit Rate with color coding
            double hitRate = stats.getHitRate();
            String hitRateClass = hitRate >= 0.8 ? "good" : hitRate >= 0.5 ? "warning" : "danger";
            html.append("<td class=\"").append(hitRateClass).append("\">")
                .append(String.format("%.2f%%", hitRate * 100)).append("</td>");
            
            // Requests
            html.append("<td class=\"value\">").append(formatNumber(stats.getRequestCount())).append("</td>");
            
            // Loads
            html.append("<td class=\"value\">").append(formatNumber(stats.getLoadCount()))
                .append(" (").append(String.format("%.1f%%", stats.getLoadSuccessRate() * 100))
                .append(" success)</td>");
            
            // Avg Load Time
            html.append("<td class=\"value\">").append(String.format("%.2f ms", stats.getAverageLoadTimeMillis())).append("</td>");
            
            // Size
            html.append("<td class=\"value\">").append(formatNumber(stats.getCurrentSize()));
            if (stats.getMaxSize() > 0) {
                html.append(" / ").append(formatNumber(stats.getMaxSize()));
            }
            html.append("</td>");
            
            // Evictions
            html.append("<td class=\"value\">").append(formatNumber(stats.getEvictionCount())).append("</td>");
            
            // Throughput
            html.append("<td class=\"value\">").append(String.format("%.1f req/s", stats.getThroughput())).append("</td>");
            
            // Uptime
            html.append("<td class=\"value\">").append(formatDuration(stats.getUptime().getSeconds())).append("</td>");
            
            html.append("</tr>\n");
        }
        
        html.append("</tbody>\n</table>\n");
        html.append("</body>\n</html>");
        
        return html.toString();
    }
    
    /**
     * 保存到文件
     */
    public void saveToFile(String content, Path filePath) throws IOException {
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(content);
            log.info("Cache statistics exported to: {}", filePath);
        }
    }
    
    /**
     * 创建导出数据结构
     */
    private Map<String, Object> createExportData(Collection<CacheStats> statsCollection) {
        Map<String, Object> exportData = new LinkedHashMap<>();
        
        exportData.put("exportTime", Instant.now().toString());
        exportData.put("cacheCount", statsCollection.size());
        
        List<Map<String, Object>> caches = statsCollection.stream()
            .map(this::statsToMap)
            .collect(Collectors.toList());
        
        exportData.put("caches", caches);
        
        // 汇总统计
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", statsCollection.stream().mapToLong(CacheStats::getRequestCount).sum());
        summary.put("totalHits", statsCollection.stream().mapToLong(CacheStats::getHitCount).sum());
        summary.put("totalMisses", statsCollection.stream().mapToLong(CacheStats::getMissCount).sum());
        summary.put("averageHitRate", statsCollection.stream().mapToDouble(CacheStats::getHitRate).average().orElse(0.0));
        summary.put("totalLoads", statsCollection.stream().mapToLong(CacheStats::getLoadCount).sum());
        summary.put("totalEvictions", statsCollection.stream().mapToLong(CacheStats::getEvictionCount).sum());
        
        exportData.put("summary", summary);
        
        return exportData;
    }
    
    /**
     * 将CacheStats转换为Map
     */
    private Map<String, Object> statsToMap(CacheStats stats) {
        Map<String, Object> map = new LinkedHashMap<>();
        
        map.put("cacheName", stats.getCacheName());
        map.put("hitCount", stats.getHitCount());
        map.put("missCount", stats.getMissCount());
        map.put("hitRate", stats.getHitRate());
        map.put("loadCount", stats.getLoadCount());
        map.put("loadSuccessCount", stats.getLoadSuccessCount());
        map.put("loadExceptionCount", stats.getLoadExceptionCount());
        map.put("loadSuccessRate", stats.getLoadSuccessRate());
        map.put("evictionCount", stats.getEvictionCount());
        map.put("putCount", stats.getPutCount());
        map.put("removeCount", stats.getRemoveCount());
        map.put("averageLoadTimeMs", stats.getAverageLoadTimeMillis());
        map.put("maxLoadTimeMs", stats.getMaxLoadTimeMillis());
        map.put("minLoadTimeMs", stats.getMinLoadTimeMillis());
        map.put("currentSize", stats.getCurrentSize());
        map.put("maxSize", stats.getMaxSize());
        map.put("throughput", stats.getThroughput());
        map.put("loadThroughput", stats.getLoadThroughput());
        map.put("evictionRate", stats.getEvictionRate());
        map.put("uptime", stats.getUptime().toString());
        map.put("uptimeSeconds", stats.getUptime().getSeconds());
        
        return map;
    }
    
    // 工具方法
    private String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
    
    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
    
    private String escapeProm(String value) {
        if (value == null) return "";
        return value.replace("\"", "\\\"")
                   .replace("\\", "\\\\")
                   .replace("\n", "\\n");
    }
    
    private String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else {
            return String.valueOf(number);
        }
    }
    
    private String formatDuration(long seconds) {
        if (seconds >= 86400) {
            return String.format("%dd %dh", seconds / 86400, (seconds % 86400) / 3600);
        } else if (seconds >= 3600) {
            return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
        } else if (seconds >= 60) {
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        } else {
            return seconds + "s";
        }
    }
}