package io.github.cascade.cache.tier;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于Redis的远程缓存层实现
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public class RedisRemoteTier<K, V> extends AbstractRemoteTier<K, V> {

    // Redis客户端接口（抽象，具体实现可以是Jedis、Lettuce等）
    private final RedisClient redisClient;

    // 序列化器
    private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;

    public RedisRemoteTier(String name, RedisClient redisClient) {
        super(name);
        this.redisClient = redisClient;
        this.keySerializer = createKeySerializer();
        this.valueSerializer = createValueSerializer();
    }

    public RedisRemoteTier(String name, RedisClient redisClient, Duration defaultTtl) {
        super(name, defaultTtl);
        this.redisClient = redisClient;
        this.keySerializer = createKeySerializer();
        this.valueSerializer = createValueSerializer();
    }

    @Override
    public V get(K key) {
        try {
            String redisKey = buildKey(key);
            String serializedValue = redisClient.get(redisKey);

            if (serializedValue != null) {
                recordHit();
                return valueSerializer.deserialize(serializedValue);
            } else {
                recordMiss();
                return null;
            }
        } catch (Exception e) {
            recordMiss();
            recordConnectionError();
            throw new RuntimeException("Error getting value from L2 cache", e);
        }
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        try {
            List<String> redisKeys = keys.stream()
                    .map(this::buildKey)
                    .collect(Collectors.toList());

            List<String> values = redisClient.mget(redisKeys);
            Map<K, V> result = new HashMap<>();

            Iterator<? extends K> keyIterator = keys.iterator();
            Iterator<String> valueIterator = values.iterator();

            while (keyIterator.hasNext() && valueIterator.hasNext()) {
                K key = keyIterator.next();
                String value = valueIterator.next();

                if (value != null) {
                    result.put(key, valueSerializer.deserialize(value));
                    recordHit();
                } else {
                    recordMiss();
                }
            }

            return result;
        } catch (Exception e) {
            // 记录所有请求为miss
            for (int i = 0; i < keys.size(); i++) {
                recordMiss();
            }
            recordConnectionError();
            throw new RuntimeException("Error getting values from L2 cache", e);
        }
    }

    @Override
    public void put(K key, V value) {
        Duration ttl = getDefaultTtl();
        if (ttl != null) {
            put(key, value, ttl);
        } else {
            try {
                String redisKey = buildKey(key);
                String serializedValue = valueSerializer.serialize(value);
                redisClient.set(redisKey, serializedValue);
            } catch (Exception e) {
                recordConnectionError();
                throw new RuntimeException("Error putting value into L2 cache", e);
            }
        }
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        try {
            String redisKey = buildKey(key);
            String serializedValue = valueSerializer.serialize(value);
            redisClient.setex(redisKey, serializedValue, ttl);
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error putting value with TTL into L2 cache", e);
        }
    }

    @Override
    public void putAll(Map<K, V> map) {
        try {
            Map<String, String> redisMap = new HashMap<>();
            for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
                String redisKey = buildKey(entry.getKey());
                String serializedValue = valueSerializer.serialize(entry.getValue());
                redisMap.put(redisKey, serializedValue);
            }

            redisClient.mset(redisMap);
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error putting values into L2 cache", e);
        }
    }

    @Override
    public void putAll(Map<K, V> map, Duration ttl) {
        if (ttl != null) {
            // 逐个设置TTL
            for (Map.Entry<K, V> entry : map.entrySet()) {
                put(entry.getKey(), entry.getValue(), ttl);
            }
        } else {
            putAll(map);
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        Duration ttl = getDefaultTtl();
        if (ttl != null) {
            return putIfAbsent(key, value, ttl);
        } else {
            try {
                String redisKey = buildKey(key);
                String serializedValue = valueSerializer.serialize(value);
                return redisClient.setnx(redisKey, serializedValue);
            } catch (Exception e) {
                recordConnectionError();
                throw new RuntimeException("Error putting if absent in L2 cache", e);
            }
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        try {
            String redisKey = buildKey(key);
            String serializedValue = valueSerializer.serialize(value);
            boolean result = redisClient.setnx(redisKey, serializedValue);
            if (result && ttl != null) {
                redisClient.expire(redisKey, ttl);
            }
            return result;
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error putting if absent with TTL in L2 cache", e);
        }
    }

    @Override
    public void evict(K key) {
        try {
            String redisKey = buildKey(key);
            if (redisClient.del(redisKey) > 0) {
                recordEviction();
            }
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error evicting key from L2 cache", e);
        }
    }

    @Override
    public void evictAll(Set<K> keys) {
        try {
            List<String> redisKeys = keys.stream()
                    .map(this::buildKey)
                    .collect(Collectors.toList());

            long deleted = redisClient.del(redisKeys.toArray(new String[0]));
            for (int i = 0; i < deleted; i++) {
                recordEviction();
            }
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error evicting keys from L2 cache", e);
        }
    }

    @Override
    public void clear() {
        try {
            // 使用模式删除所有相关键
            String pattern = getKeyPrefix() + "*";
            redisClient.deleteByPattern(pattern);
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error clearing L2 cache", e);
        }
    }

    @Override
    public long size() {
        try {
            String pattern = getKeyPrefix() + "*";
            return redisClient.countByPattern(pattern);
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error getting size of L2 cache", e);
        }
    }

    @Override
    public boolean containsKey(K key) {
        try {
            String redisKey = buildKey(key);
            return redisClient.exists(redisKey);
        } catch (Exception e) {
            recordConnectionError();
            return false;
        }
    }

    @Override
    public void cleanUp() {
        // Redis自动处理过期键，这里可以执行一些清理逻辑
    }

    // ==================== RemoteTier特有方法实现 ====================

    @Override
    public Duration getTimeToLive(K key) {
        try {
            String redisKey = buildKey(key);
            long ttl = redisClient.ttl(redisKey);
            return ttl > 0 ? Duration.ofSeconds(ttl) : null;
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error getting TTL from L2 cache", e);
        }
    }

    @Override
    public boolean expire(K key, Duration ttl) {
        try {
            String redisKey = buildKey(key);
            redisClient.expire(redisKey, ttl);
            return true;
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error setting expiration in L2 cache", e);
        }
    }

    @Override
    public boolean persist(K key) {
        try {
            String redisKey = buildKey(key);
            return redisClient.persist(redisKey);
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error persisting key in L2 cache", e);
        }
    }

    @Override
    public Set<K> keys(String pattern) {
        try {
            String redisPattern = getKeyPrefix() + pattern;
            Set<String> redisKeys = redisClient.keys(redisPattern);
            return redisKeys.stream()
                    .map(redisKey -> redisKey.substring(getKeyPrefix().length()))
                    .map(keyStr -> keySerializer.deserialize(keyStr))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error getting keys by pattern from L2 cache", e);
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return redisClient.ping();
        } catch (Exception e) {
            recordConnectionError();
            return false;
        }
    }

    @Override
    public Map<String, Object> getClusterInfo() {
        try {
            String info = redisClient.info();
            Map<String, Object> result = new HashMap<>();
            result.put("info", info);
            result.put("connected", isConnected());
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            result.put("connected", false);
            return result;
        }
    }

    @Override
    public Map<String, String> getServerInfo() {
        Map<String, String> info = new HashMap<>();
        try {
            info.put("connected", String.valueOf(isConnected()));
            info.put("ping", String.valueOf(ping()));
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }
        return info;
    }

    @Override
    public Map<String, Object> getMemoryInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("estimated_size", estimatedSize());
        info.put("tier", getTier().name());
        return info;
    }

    @Override
    public Object eval(String script, List<K> keys, Object... args) {
        try {
            List<String> redisKeys = keys.stream()
                    .map(this::buildKey)
                    .collect(Collectors.toList());

            return redisClient.eval(script, redisKeys, Arrays.asList(args));
        } catch (Exception e) {
            recordConnectionError();
            throw new RuntimeException("Error executing Lua script", e);
        }
    }

    @Override
    public long publish(String channel, Object message) {
        try {
            // 简化实现，实际需要根据具体Redis客户端进行发布
            return 0;
        } catch (Exception e) {
            recordConnectionError();
            return 0;
        }
    }

    @Override
    protected Pipeline createPipeline() {
        return new RedisPipeline();
    }

    @Override
    protected Transaction createTransaction() {
        return new RedisTransaction();
    }

    @Override
    protected void doSubscribe(MessageListener listener, String... channels) {
        // Redis客户端的简化实现
        // 实际实现中需要根据具体的Redis客户端进行订阅
    }

    @Override
    protected void doPsubscribe(MessageListener listener, String... patterns) {
        // Redis客户端的简化实现
        // 实际实现中需要根据具体的Redis客户端进行模式订阅
    }

    @Override
    protected void doUnsubscribe(String... channels) {
        // Redis客户端的简化实现
        // 实际实现中需要根据具体的Redis客户端进行取消订阅
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private Serializer<K> createKeySerializer() {
        // 简化实现，实际应该根据配置选择序列化器
        return (Serializer<K>) new StringSerializer();
    }

    @SuppressWarnings("unchecked")
    private Serializer<V> createValueSerializer() {
        // 简化实现，实际应该根据配置选择序列化器
        return (Serializer<V>) new JsonSerializer();
    }

    @Override
    public String toString() {
        return String.format("RedisRemoteTier{name='%s', connected=%s, stats=%s}",
                getName(), isConnected(), getStats());
    }

    // ==================== 内部实现类 ====================

    /**
     * Redis管道实现
     */
    private class RedisPipeline implements Pipeline {
        private final List<PipelineCommand> commands = new ArrayList<>();

        @Override
        public Pipeline put(Object key, Object value) {
            commands.add(new PipelineCommand("SET", Arrays.asList(key, value)));
            return this;
        }

        @Override
        public Pipeline put(Object key, Object value, Duration ttl) {
            commands.add(new PipelineCommand("SETEX", Arrays.asList(key, ttl.getSeconds(), value)));
            return this;
        }

        @Override
        public Pipeline evict(Object key) {
            commands.add(new PipelineCommand("DEL", Arrays.asList(key)));
            return this;
        }

        @Override
        public Pipeline get(Object key) {
            commands.add(new PipelineCommand("GET", Arrays.asList(key)));
            return this;
        }

        @Override
        public Pipeline exists(Object key) {
            commands.add(new PipelineCommand("EXISTS", Arrays.asList(key)));
            return this;
        }

        @Override
        public void close() {
            // 清理资源
            commands.clear();
        }

        @Override
        public List<Object> execute() {
            try {
                recordCommand(System.nanoTime());
                return redisClient.executePipeline(commands);
            } catch (Exception e) {
                recordConnectionError();
                throw new RuntimeException("Error executing pipeline", e);
            }
        }
    }

    // ==================== Redis事务实现 ====================
    
    private class RedisTransaction implements Transaction {
        private final List<PipelineCommand> commands = new ArrayList<>();
        private final Set<String> watchedKeys = new HashSet<>();

        @Override
        public Transaction put(Object key, Object value) {
            commands.add(new PipelineCommand("SET", Arrays.asList(key, value)));
            return this;
        }

        @Override
        public Transaction put(Object key, Object value, Duration ttl) {
            commands.add(new PipelineCommand("SETEX", Arrays.asList(key, ttl.getSeconds(), value)));
            return this;
        }

        @Override
        public Transaction evict(Object key) {
            commands.add(new PipelineCommand("DEL", Arrays.asList(key)));
            return this;
        }

        @Override
        public Transaction watch(Object... keys) {
            for (Object key : keys) {
                watchedKeys.add(key.toString());
            }
            return this;
        }

        @Override
        public Transaction unwatch() {
            watchedKeys.clear();
            return this;
        }

        @Override
        public List<Object> exec() {
            try {
                recordCommand(System.nanoTime());
                // 简化实现，实际应该根据具体Redis客户端实现事务
                return redisClient.executePipeline(commands);
            } catch (Exception e) {
                recordConnectionError();
                return null; // 表示事务被丢弃
            }
        }

        @Override
        public void discard() {
            commands.clear();
            watchedKeys.clear();
        }

        @Override
        public void close() {
            discard();
        }
    }

    /**
     * 管道命令
     */
    private static class PipelineCommand {
        private final String command;
        private final List<Object> args;

        public PipelineCommand(String command, List<Object> args) {
            this.command = command;
            this.args = args;
        }

        public String getCommand() {
            return command;
        }

        public List<Object> getArgs() {
            return args;
        }
    }

    /**
     * Redis客户端接口
     */
    public interface RedisClient {
        String get(String key);

        List<String> mget(List<String> keys);

        void set(String key, String value);

        void setex(String key, String value, Duration ttl);

        void mset(Map<String, String> map);

        void expire(String key, Duration ttl);

        long del(String... keys);

        void deleteByPattern(String pattern);

        long countByPattern(String pattern);

        boolean ping();

        String info();

        Object eval(String script, List<String> keys, List<Object> args);

        List<Object> executePipeline(List<PipelineCommand> commands);

        boolean setnx(String key, String value);

        long ttl(String key);

        boolean exists(String key);

        boolean persist(String key);

        Set<String> keys(String pattern);
    }

    /**
     * 序列化器接口
     */
    public interface Serializer<T> {
        String serialize(T object);

        T deserialize(String data);
    }

    /**
     * 字符串序列化器
     */
    private static class StringSerializer implements Serializer<Object> {
        @Override
        public String serialize(Object object) {
            return object != null ? object.toString() : null;
        }

        @Override
        public Object deserialize(String data) {
            return data;
        }
    }

    /**
     * JSON序列化器（简化实现）
     */
    private static class JsonSerializer implements Serializer<Object> {
        @Override
        public String serialize(Object object) {
            // 简化实现，实际应该使用Jackson或Gson
            return object != null ? object.toString() : null;
        }

        @Override
        public Object deserialize(String data) {
            // 简化实现，实际应该使用JSON库反序列化
            return data;
        }
    }
}