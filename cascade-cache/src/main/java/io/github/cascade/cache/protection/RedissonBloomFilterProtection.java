package io.github.cascade.cache.protection;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于Redisson的分布式布隆过滤器防穿透保护
 * 使用Redisson的RBloomFilter实现，支持分布式环境下的布隆过滤器
 *
 * @author Cascade Framework
 */
public class RedissonBloomFilterProtection implements CascadeBloomFilter {
    
    private static final Logger log = LoggerFactory.getLogger(RedissonBloomFilterProtection.class);
    
    private final RBloomFilter<String> bloomFilter;
    private final RedissonClient redissonClient;
    private final String filterName;
    private final long expectedElements;
    private final double falsePositiveRate;
    private final AtomicLong localAddCount = new AtomicLong(0);
    private final AtomicLong localCheckCount = new AtomicLong(0);
    
    /**
     * 构造函数
     *
     * @param redissonClient Redis客户端
     * @param filterName 过滤器名称
     * @param expectedElements 预期元素数量
     * @param falsePositiveRate 期望的假阳性率
     */
    public RedissonBloomFilterProtection(RedissonClient redissonClient, 
                                       String filterName,
                                       long expectedElements, 
                                       double falsePositiveRate) {
        this.redissonClient = redissonClient;
        this.filterName = filterName;
        this.expectedElements = expectedElements;
        this.falsePositiveRate = falsePositiveRate;
        
        // 获取或创建布隆过滤器
        this.bloomFilter = redissonClient.getBloomFilter(filterName);
        
        // 初始化布隆过滤器（如果尚未初始化）
        initializeBloomFilter();
        
        log.info("Initialized Redisson BloomFilter: name={}, expectedElements={}, falsePositiveRate={}", 
            filterName, expectedElements, falsePositiveRate);
    }
    
    /**
     * 初始化布隆过滤器
     */
    private void initializeBloomFilter() {
        try {
            // 检查布隆过滤器是否已经初始化
            if (!bloomFilter.isExists()) {
                // 如果不存在，则初始化
                boolean initialized = bloomFilter.tryInit(expectedElements, falsePositiveRate);
                if (initialized) {
                    log.info("BloomFilter initialized successfully: {}", filterName);
                } else {
                    log.warn("BloomFilter {} may have been initialized by another instance", filterName);
                }
            } else {
                log.debug("BloomFilter {} already exists", filterName);
            }
        } catch (Exception e) {
            log.error("Failed to initialize BloomFilter {}: {}", filterName, e.getMessage(), e);
            throw new RuntimeException("BloomFilter initialization failed", e);
        }
    }
    
    /**
     * 添加元素到布隆过滤器
     *
     * @param element 要添加的元素
     */
    public void add(String element) {
        if (element == null || element.isEmpty()) {
            return;
        }
        
        try {
            bloomFilter.add(element);
            localAddCount.incrementAndGet();
            log.debug("Added element to BloomFilter: {}", element);
        } catch (Exception e) {
            log.error("Failed to add element {} to BloomFilter {}: {}", element, filterName, e.getMessage());
        }
    }
    
    /**
     * 批量添加元素到布隆过滤器
     *
     * @param elements 要添加的元素集合
     */
    public void addAll(Iterable<String> elements) {
        if (elements == null) {
            return;
        }
        
        try {
            for (String element : elements) {
                if (element != null && !element.isEmpty()) {
                    bloomFilter.add(element);
                    localAddCount.incrementAndGet();
                }
            }
            log.debug("Batch added elements to BloomFilter: {}", filterName);
        } catch (Exception e) {
            log.error("Failed to batch add elements to BloomFilter {}: {}", filterName, e.getMessage());
        }
    }
    
    /**
     * 检查元素是否可能存在
     *
     * @param element 要检查的元素
     * @return true表示可能存在，false表示一定不存在
     */
    public boolean mightContain(String element) {
        if (element == null || element.isEmpty()) {
            return false;
        }
        
        try {
            localCheckCount.incrementAndGet();
            boolean result = bloomFilter.contains(element);
            log.debug("BloomFilter check: element={}, result={}", element, result);
            return result;
        } catch (Exception e) {
            log.error("Failed to check element {} in BloomFilter {}: {}", element, filterName, e.getMessage());
            // 异常情况下保守返回true，避免误判为不存在
            return true;
        }
    }
    
    /**
     * 检查元素是否一定不存在
     *
     * @param element 要检查的元素
     * @return true表示一定不存在，false表示可能存在
     */
    public boolean definitelyNotContain(String element) {
        return !mightContain(element);
    }
    
    /**
     * 获取布隆过滤器的预计元素数量
     *
     * @return 预计元素数量
     */
    public long getExpectedInsertions() {
        try {
            return bloomFilter.getExpectedInsertions();
        } catch (Exception e) {
            log.error("Failed to get expected insertions for BloomFilter {}: {}", filterName, e.getMessage());
            return expectedElements;
        }
    }
    
    /**
     * 获取布隆过滤器的假阳性率
     *
     * @return 假阳性率
     */
    public double getFalseProbability() {
        try {
            return bloomFilter.getFalseProbability();
        } catch (Exception e) {
            log.error("Failed to get false probability for BloomFilter {}: {}", filterName, e.getMessage());
            return falsePositiveRate;
        }
    }
    
    /**
     * 获取布隆过滤器的哈希函数数量
     *
     * @return 哈希函数数量
     */
    public long getHashIterations() {
        try {
            return bloomFilter.getHashIterations();
        } catch (Exception e) {
            log.error("Failed to get hash iterations for BloomFilter {}: {}", filterName, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 获取布隆过滤器的大小（位数）
     *
     * @return 位数组大小
     */
    public long getSize() {
        try {
            return bloomFilter.getSize();
        } catch (Exception e) {
            log.error("Failed to get size for BloomFilter {}: {}", filterName, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 检查布隆过滤器是否存在
     *
     * @return 是否存在
     */
    public boolean exists() {
        try {
            return bloomFilter.isExists();
        } catch (Exception e) {
            log.error("Failed to check existence of BloomFilter {}: {}", filterName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 清空布隆过滤器
     */
    public void clear() {
        try {
            bloomFilter.delete();
            // 重新初始化
            initializeBloomFilter();
            localAddCount.set(0);
            localCheckCount.set(0);
            log.info("BloomFilter {} cleared and reinitialized", filterName);
        } catch (Exception e) {
            log.error("Failed to clear BloomFilter {}: {}", filterName, e.getMessage());
        }
    }
    
    /**
     * 删除布隆过滤器
     */
    public void delete() {
        try {
            bloomFilter.delete();
            log.info("BloomFilter {} deleted", filterName);
        } catch (Exception e) {
            log.error("Failed to delete BloomFilter {}: {}", filterName, e.getMessage());
        }
    }
    
    /**
     * 获取过滤器名称
     *
     * @return 过滤器名称
     */
    public String getFilterName() {
        return filterName;
    }
    
    /**
     * 获取统计信息
     *
     * @return 统计信息
     */
    @Override
    public CascadeBloomFilter.BloomFilterStats getStats() {
        try {
            long expectedInsertions = getExpectedInsertions();
            double falseProbability = getFalseProbability();
            long hashIterations = getHashIterations();
            long size = getSize();
            long localAdds = localAddCount.get();
            long localChecks = localCheckCount.get();
            
            return new BloomFilterStats(
                localAdds,              // 本地添加次数（近似）
                expectedInsertions,     // 预期元素数量
                size,                   // 位数组大小  
                hashIterations,         // 哈希函数数量
                falseProbability,       // 假阳性率
                localChecks            // 本地检查次数
            );
        } catch (Exception e) {
            log.error("Failed to get stats for BloomFilter {}: {}", filterName, e.getMessage());
            return new BloomFilterStats(0, expectedElements, 0, 0, falsePositiveRate, 0);
        }
    }
    
    /**
     * 布隆过滤器统计信息（兼容原有接口）
     */
    public static class BloomFilterStats implements CascadeBloomFilter.BloomFilterStats {
        private final long addedElements;
        private final long expectedElements;
        private final long bitArraySize;
        private final long hashFunctionCount;
        private final double falsePositiveRate;
        private final long checkCount;
        
        public BloomFilterStats(long addedElements, 
                               long expectedElements,
                               long bitArraySize, 
                               long hashFunctionCount,
                               double falsePositiveRate,
                               long checkCount) {
            this.addedElements = addedElements;
            this.expectedElements = expectedElements;
            this.bitArraySize = bitArraySize;
            this.hashFunctionCount = hashFunctionCount;
            this.falsePositiveRate = falsePositiveRate;
            this.checkCount = checkCount;
        }
        
        @Override
        public long getAddedElements() { return addedElements; }
        
        @Override
        public long getExpectedElements() { return expectedElements; }
        
        public long getBitArraySize() { return bitArraySize; }
        public long getHashFunctionCount() { return hashFunctionCount; }
        
        @Override
        public double getFalsePositiveRate() { return falsePositiveRate; }
        
        public long getCheckCount() { return checkCount; }
        
        @Override
        public double getElementUtilization() { 
            return expectedElements > 0 ? (double) addedElements / expectedElements : 0.0; 
        }
        
        @Override
        public String toString() {
            return String.format(
                "RedissonBloomFilterStats{addedElements=%d, expectedElements=%d, bitArraySize=%d, " +
                "hashFunctionCount=%d, falsePositiveRate=%.6f, checkCount=%d, elementUtilization=%.2f%%}",
                addedElements, expectedElements, bitArraySize, hashFunctionCount,
                falsePositiveRate, checkCount, getElementUtilization() * 100
            );
        }
    }
    
    /**
     * 创建布隆过滤器构建器
     */
    public static Builder builder(RedissonClient redissonClient) {
        return new Builder(redissonClient);
    }
    
    /**
     * 构建器
     */
    public static class Builder {
        private final RedissonClient redissonClient;
        private String filterName;
        private long expectedElements = 10000;
        private double falsePositiveRate = 0.01;
        
        public Builder(RedissonClient redissonClient) {
            this.redissonClient = redissonClient;
        }
        
        public Builder filterName(String filterName) {
            this.filterName = filterName;
            return this;
        }
        
        public Builder expectedElements(long expectedElements) {
            this.expectedElements = expectedElements;
            return this;
        }
        
        public Builder falsePositiveRate(double falsePositiveRate) {
            this.falsePositiveRate = falsePositiveRate;
            return this;
        }
        
        public RedissonBloomFilterProtection build() {
            if (filterName == null || filterName.trim().isEmpty()) {
                throw new IllegalArgumentException("Filter name cannot be null or empty");
            }
            return new RedissonBloomFilterProtection(redissonClient, filterName, expectedElements, falsePositiveRate);
        }
    }
}