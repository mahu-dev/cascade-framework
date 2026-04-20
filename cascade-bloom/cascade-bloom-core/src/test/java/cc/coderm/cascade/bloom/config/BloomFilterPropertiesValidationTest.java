package cc.coderm.cascade.bloom.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BloomFilterPropertiesValidationTest {

    @Test
    void afterPropertiesSet_ShouldRejectBlankFilterName() {
        BloomFilterProperties properties = new BloomFilterProperties();

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("   ");
        properties.setFilters(List.of(definition));

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] filters[0].name must not be blank", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectDuplicateFilterNameAfterTrim() {
        BloomFilterProperties properties = new BloomFilterProperties();

        BloomFilterProperties.BloomFilterDefinition first = new BloomFilterProperties.BloomFilterDefinition();
        first.setName("user-bloom");
        BloomFilterProperties.BloomFilterDefinition second = new BloomFilterProperties.BloomFilterDefinition();
        second.setName(" user-bloom ");
        properties.setFilters(List.of(first, second));

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] Duplicate filter name is not allowed: user-bloom", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectInvalidExpectedInsertionsInFilterDefinition() {
        BloomFilterProperties properties = new BloomFilterProperties();

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("user-bloom");
        definition.setExpectedInsertions(0L);
        properties.setFilters(List.of(definition));

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] filters[0].expectedInsertions must be positive when provided", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectInvalidFalseProbabilityInFilterDefinition() {
        BloomFilterProperties properties = new BloomFilterProperties();

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("user-bloom");
        definition.setFalseProbability(1.0);
        properties.setFilters(List.of(definition));

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] filters[0].falseProbability must be in range (0, 1) when provided", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldNormalizeFilterNameByTrimming() {
        BloomFilterProperties properties = new BloomFilterProperties();

        BloomFilterProperties.BloomFilterDefinition definition = new BloomFilterProperties.BloomFilterDefinition();
        definition.setName("  user-bloom  ");
        definition.setExpectedInsertions(100L);
        definition.setFalseProbability(0.01);
        properties.setFilters(List.of(definition));

        properties.afterPropertiesSet();

        assertEquals("user-bloom", definition.getName());
    }

    @Test
    void afterPropertiesSet_ShouldRejectNegativeCacheExistenceProbeIntervalMillis() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setCacheExistenceProbeIntervalMillis(-1L);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] cacheExistenceProbeIntervalMillis must be >= 0", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectNonPositiveCacheExistenceProbeTimeoutMillis() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setCacheExistenceProbeTimeoutMillis(0L);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] cacheExistenceProbeTimeoutMillis must be > 0", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectNullInitializationWaitTimeout() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setInitializationWaitTimeout(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] initializationWaitTimeout must not be null", exception.getMessage());
    }

    @Test
    void afterPropertiesSet_ShouldRejectNonPositiveInitializationWaitTimeout() {
        BloomFilterProperties properties = new BloomFilterProperties();
        properties.setInitializationWaitTimeout(Duration.ZERO);

        IllegalStateException exception = assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("[cascade-bloom] initializationWaitTimeout must be > 0", exception.getMessage());
    }
}
