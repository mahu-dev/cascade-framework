package io.github.cascade.cache.serialization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 序列化器性能基准测试
 * 用于比较不同序列化器的性能特征
 */
public class SerializerBenchmark {
    
    private static final Logger log = LoggerFactory.getLogger(SerializerBenchmark.class);
    
    private final List<CacheSerializer<?>> serializers = new ArrayList<>();
    private final Map<String, BenchmarkResult> results = new ConcurrentHashMap<>();
    
    /**
     * 添加要测试的序列化器
     */
    public SerializerBenchmark addSerializer(CacheSerializer<?> serializer) {
        serializers.add(serializer);
        return this;
    }
    
    /**
     * 运行基准测试
     */
    public BenchmarkReport runBenchmark() {
        return runBenchmark(new BenchmarkConfig());
    }
    
    /**
     * 运行基准测试（自定义配置）
     */
    public BenchmarkReport runBenchmark(BenchmarkConfig config) {
        log.info("Starting serializer benchmark with {} serializers", serializers.size());
        
        results.clear();
        
        for (CacheSerializer<?> serializer : serializers) {
            try {
                BenchmarkResult result = benchmarkSerializer(serializer, config);
                results.put(serializer.getName(), result);
                log.info("Completed benchmark for serializer: {} - {}", serializer.getName(), result);
            } catch (Exception e) {
                log.error("Error benchmarking serializer: {}", serializer.getName(), e);
                results.put(serializer.getName(), BenchmarkResult.error(serializer.getName(), e));
            }
        }
        
        BenchmarkReport report = new BenchmarkReport(results, config);
        log.info("Benchmark completed. Best performer: {}", report.getBestPerformer());
        
        return report;
    }
    
    @SuppressWarnings("unchecked")
    private BenchmarkResult benchmarkSerializer(CacheSerializer<?> serializer, BenchmarkConfig config) {
        CacheSerializer<Object> objSerializer = (CacheSerializer<Object>) serializer;
        
        // 预热
        warmup(objSerializer, config);
        
        BenchmarkResult.Builder resultBuilder = BenchmarkResult.builder(serializer.getName());
        
        // 测试不同类型的数据
        for (TestDataType dataType : TestDataType.values()) {
            Object testData = generateTestData(dataType, config);
            BenchmarkResult.TypeResult typeResult = benchmarkDataType(objSerializer, testData, config);
            resultBuilder.addTypeResult(dataType, typeResult);
        }
        
        return resultBuilder.build();
    }
    
    private void warmup(CacheSerializer<Object> serializer, BenchmarkConfig config) {
        try {
            Object warmupData = "warmup data";
            for (int i = 0; i < config.getWarmupIterations(); i++) {
                byte[] serialized = serializer.serialize(warmupData);
                serializer.deserialize(serialized);
            }
        } catch (Exception e) {
            log.warn("Warmup failed for serializer: {}", serializer.getName(), e);
        }
    }
    
    private BenchmarkResult.TypeResult benchmarkDataType(CacheSerializer<Object> serializer, 
                                                        Object testData, BenchmarkConfig config) {
        
        long totalSerializeTime = 0;
        long totalDeserializeTime = 0;
        long totalSerializedSize = 0;
        int successfulIterations = 0;
        
        for (int i = 0; i < config.getIterations(); i++) {
            try {
                // 序列化测试
                Instant serializeStart = Instant.now();
                byte[] serialized = serializer.serialize(testData);
                Duration serializeDuration = Duration.between(serializeStart, Instant.now());
                
                totalSerializeTime += serializeDuration.toNanos();
                totalSerializedSize += serialized.length;
                
                // 反序列化测试
                Instant deserializeStart = Instant.now();
                Object deserialized = serializer.deserialize(serialized);
                Duration deserializeDuration = Duration.between(deserializeStart, Instant.now());
                
                totalDeserializeTime += deserializeDuration.toNanos();
                
                // 验证正确性（简单验证）
                if (deserialized != null) {
                    successfulIterations++;
                }
                
            } catch (Exception e) {
                log.debug("Iteration {} failed for serializer {}: {}", i, serializer.getName(), e.getMessage());
            }
        }
        
        return new BenchmarkResult.TypeResult(
            successfulIterations,
            config.getIterations(),
            totalSerializeTime / 1_000_000.0, // 转换为毫秒
            totalDeserializeTime / 1_000_000.0,
            (double) totalSerializedSize / successfulIterations
        );
    }
    
    private Object generateTestData(TestDataType dataType, BenchmarkConfig config) {
        return switch (dataType) {
            case STRING -> generateStringData(config.getDataSize());
            case INTEGER -> ThreadLocalRandom.current().nextInt();
            case LONG -> ThreadLocalRandom.current().nextLong();
            case DOUBLE -> ThreadLocalRandom.current().nextDouble();
            case LIST -> generateListData(config.getDataSize());
            case MAP -> generateMapData(config.getDataSize());
            case COMPLEX_OBJECT -> generateComplexObject(config.getDataSize());
        };
    }
    
    private String generateStringData(int size) {
        Random random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }
    
    private List<String> generateListData(int size) {
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add("item_" + i);
        }
        return list;
    }
    
    private Map<String, Object> generateMapData(int size) {
        Map<String, Object> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put("key_" + i, "value_" + i);
        }
        return map;
    }
    
    private TestObject generateComplexObject(int size) {
        return new TestObject("test_object", size, new Date(), 
            generateListData(Math.min(size, 100)), 
            generateMapData(Math.min(size, 50)));
    }
    
    /**
     * 基准测试配置
     */
    public static class BenchmarkConfig {
        private int iterations = 10000;
        private int warmupIterations = 1000;
        private int dataSize = 100;
        
        public int getIterations() { return iterations; }
        public int getWarmupIterations() { return warmupIterations; }
        public int getDataSize() { return dataSize; }
        
        public BenchmarkConfig setIterations(int iterations) {
            this.iterations = iterations;
            return this;
        }
        
        public BenchmarkConfig setWarmupIterations(int warmupIterations) {
            this.warmupIterations = warmupIterations;
            return this;
        }
        
        public BenchmarkConfig setDataSize(int dataSize) {
            this.dataSize = dataSize;
            return this;
        }
    }
    
    /**
     * 测试数据类型
     */
    public enum TestDataType {
        STRING, INTEGER, LONG, DOUBLE, LIST, MAP, COMPLEX_OBJECT
    }
    
    /**
     * 基准测试结果
     */
    public static class BenchmarkResult {
        private final String serializerName;
        private final Map<TestDataType, TypeResult> typeResults;
        private final boolean hasError;
        private final Exception error;
        
        private BenchmarkResult(String serializerName, Map<TestDataType, TypeResult> typeResults, 
                              boolean hasError, Exception error) {
            this.serializerName = serializerName;
            this.typeResults = typeResults;
            this.hasError = hasError;
            this.error = error;
        }
        
        public static Builder builder(String serializerName) {
            return new Builder(serializerName);
        }
        
        public static BenchmarkResult error(String serializerName, Exception error) {
            return new BenchmarkResult(serializerName, Collections.emptyMap(), true, error);
        }
        
        public String getSerializerName() { return serializerName; }
        public Map<TestDataType, TypeResult> getTypeResults() { return typeResults; }
        public boolean hasError() { return hasError; }
        public Exception getError() { return error; }
        
        public double getOverallScore() {
            if (hasError) return 0.0;
            return typeResults.values().stream()
                .mapToDouble(TypeResult::getScore)
                .average()
                .orElse(0.0);
        }
        
        @Override
        public String toString() {
            if (hasError) {
                return String.format("BenchmarkResult{serializer=%s, error=%s}", 
                    serializerName, error.getMessage());
            }
            return String.format("BenchmarkResult{serializer=%s, score=%.2f}", 
                serializerName, getOverallScore());
        }
        
        public static class Builder {
            private final String serializerName;
            private final Map<TestDataType, TypeResult> typeResults = new EnumMap<>(TestDataType.class);
            
            public Builder(String serializerName) {
                this.serializerName = serializerName;
            }
            
            public Builder addTypeResult(TestDataType type, TypeResult result) {
                typeResults.put(type, result);
                return this;
            }
            
            public BenchmarkResult build() {
                return new BenchmarkResult(serializerName, typeResults, false, null);
            }
        }
        
        /**
         * 单个数据类型的测试结果
         */
        public static class TypeResult {
            private final int successfulIterations;
            private final int totalIterations;
            private final double avgSerializeTimeMs;
            private final double avgDeserializeTimeMs;
            private final double avgSerializedSize;
            
            public TypeResult(int successfulIterations, int totalIterations,
                            double avgSerializeTimeMs, double avgDeserializeTimeMs,
                            double avgSerializedSize) {
                this.successfulIterations = successfulIterations;
                this.totalIterations = totalIterations;
                this.avgSerializeTimeMs = avgSerializeTimeMs;
                this.avgDeserializeTimeMs = avgDeserializeTimeMs;
                this.avgSerializedSize = avgSerializedSize;
            }
            
            public double getSuccessRate() {
                return totalIterations > 0 ? (double) successfulIterations / totalIterations : 0.0;
            }
            
            public double getTotalTimeMs() {
                return avgSerializeTimeMs + avgDeserializeTimeMs;
            }
            
            public double getScore() {
                // 综合考虑成功率、速度和大小的评分
                double successRate = getSuccessRate();
                double speedScore = 1000.0 / Math.max(getTotalTimeMs(), 0.001); // 速度分
                double sizeScore = 1000.0 / Math.max(avgSerializedSize, 1.0); // 大小分
                
                return successRate * (speedScore + sizeScore) / 2;
            }
            
            public int getSuccessfulIterations() { return successfulIterations; }
            public int getTotalIterations() { return totalIterations; }
            public double getAvgSerializeTimeMs() { return avgSerializeTimeMs; }
            public double getAvgDeserializeTimeMs() { return avgDeserializeTimeMs; }
            public double getAvgSerializedSize() { return avgSerializedSize; }
        }
    }
    
    /**
     * 基准测试报告
     */
    public static class BenchmarkReport {
        private final Map<String, BenchmarkResult> results;
        private final BenchmarkConfig config;
        
        public BenchmarkReport(Map<String, BenchmarkResult> results, BenchmarkConfig config) {
            this.results = results;
            this.config = config;
        }
        
        public Map<String, BenchmarkResult> getResults() {
            return results;
        }
        
        public BenchmarkConfig getConfig() {
            return config;
        }
        
        public String getBestPerformer() {
            return results.values().stream()
                .filter(r -> !r.hasError())
                .max(Comparator.comparingDouble(BenchmarkResult::getOverallScore))
                .map(BenchmarkResult::getSerializerName)
                .orElse("None");
        }
        
        public void printReport() {
            System.out.println("=== Serializer Benchmark Report ===");
            System.out.printf("Iterations: %d, Warmup: %d, Data Size: %d%n", 
                config.getIterations(), config.getWarmupIterations(), config.getDataSize());
            System.out.println();
            
            results.values().stream()
                .sorted(Comparator.comparingDouble(BenchmarkResult::getOverallScore).reversed())
                .forEach(result -> {
                    System.out.printf("%-15s: Score=%.2f%n", 
                        result.getSerializerName(), result.getOverallScore());
                    if (result.hasError()) {
                        System.out.printf("                 Error: %s%n", result.getError().getMessage());
                    }
                });
        }
    }
    
    /**
     * 测试对象
     */
    public static class TestObject {
        private String name;
        private int value;
        private Date timestamp;
        private List<String> items;
        private Map<String, Object> properties;
        
        public TestObject() {}
        
        public TestObject(String name, int value, Date timestamp, 
                         List<String> items, Map<String, Object> properties) {
            this.name = name;
            this.value = value;
            this.timestamp = timestamp;
            this.items = items;
            this.properties = properties;
        }
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        public Date getTimestamp() { return timestamp; }
        public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
        public List<String> getItems() { return items; }
        public void setItems(List<String> items) { this.items = items; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }
}