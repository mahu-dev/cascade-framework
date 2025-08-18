package io.github.cascade.cache.tier;

import io.github.cascade.cache.api.Cache;
import io.github.cascade.cache.api.CacheTier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 远程缓存层接口（L2缓存）
 * 定义远程缓存特有的功能，如TTL管理、集群操作、脚本执行等
 *
 * @param <K> 键类型
 * @param <V> 值类型
 * @author cascade
 */
public interface RemoteTier<K, V> extends Cache<K, V> {

    /**
     * 获取缓存层级类型
     * 远程缓存固定返回L2
     *
     * @return L2缓存层级
     */
    @Override
    default CacheTier getTier() {
        return CacheTier.L2;
    }

    // ==================== TTL管理 ====================

    /**
     * 存储缓存值，指定过期时间
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   过期时间
     */
    void put(K key, V value, Duration ttl);

    /**
     * 批量存储缓存值，指定过期时间
     *
     * @param map 键值对映射
     * @param ttl 过期时间
     */
    void putAll(Map<K, V> map, Duration ttl);

    /**
     * 如果键不存在则存储，指定过期时间
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   过期时间
     * @return 如果存储成功返回true，否则返回false
     */
    boolean putIfAbsent(K key, V value, Duration ttl);

    /**
     * 获取键的剩余生存时间
     *
     * @param key 缓存键
     * @return 剩余生存时间，null表示永不过期，Duration.ZERO表示已过期
     */
    Duration getTimeToLive(K key);

    /**
     * 设置键的过期时间
     *
     * @param key 缓存键
     * @param ttl 过期时间
     * @return 是否设置成功
     */
    boolean expire(K key, Duration ttl);

    /**
     * 移除键的过期时间，使其持久化
     *
     * @param key 缓存键
     * @return 是否移除成功
     */
    boolean persist(K key);

    // ==================== 模式匹配和批量操作 ====================

    /**
     * 根据模式获取匹配的键集合
     *
     * @param pattern 匹配模式（支持通配符）
     * @return 匹配的键集合
     */
    Set<K> keys(String pattern);

    /**
     * 根据模式删除匹配的键
     *
     * @param pattern 匹配模式
     * @return 删除的键数量
     */
    long deleteByPattern(String pattern);

    /**
     * 根据模式统计匹配的键数量
     *
     * @param pattern 匹配模式
     * @return 匹配的键数量
     */
    long countByPattern(String pattern);

    // ==================== 连接和集群管理 ====================

    /**
     * 检查与远程缓存服务的连接状态
     *
     * @return 是否连接正常
     */
    boolean isConnected();

    /**
     * 发送ping命令测试连接
     *
     * @return ping响应时间（毫秒），-1表示连接失败
     */
    long ping();

    /**
     * 获取集群信息
     *
     * @return 集群信息映射
     */
    Map<String, Object> getClusterInfo();

    /**
     * 获取服务器信息
     *
     * @return 服务器信息
     */
    Map<String, String> getServerInfo();

    /**
     * 获取内存使用信息
     *
     * @return 内存信息
     */
    Map<String, Object> getMemoryInfo();

    // ==================== 脚本执行 ====================

    /**
     * 执行Lua脚本
     *
     * @param script 脚本内容
     * @param keys   键列表
     * @param args   参数列表
     * @return 脚本执行结果
     */
    Object eval(String script, List<K> keys, Object... args);

    /**
     * 执行预加载的Lua脚本
     *
     * @param scriptSha 脚本SHA1哈希
     * @param keys      键列表
     * @param args      参数列表
     * @return 脚本执行结果
     */
    Object evalSha(String scriptSha, List<K> keys, Object... args);

    /**
     * 加载Lua脚本到服务器
     *
     * @param script 脚本内容
     * @return 脚本的SHA1哈希
     */
    String scriptLoad(String script);

    /**
     * 检查脚本是否存在
     *
     * @param scriptSha 脚本SHA1哈希
     * @return 是否存在
     */
    boolean scriptExists(String scriptSha);

    // ==================== 管道和事务 ====================

    /**
     * 开始管道操作
     * 管道可以批量执行命令，减少网络往返次数
     *
     * @return 管道对象
     */
    Pipeline pipeline();

    /**
     * 开始事务操作
     * 事务保证原子性执行
     *
     * @return 事务对象
     */
    Transaction transaction();

    /**
     * 管道操作接口
     */
    interface Pipeline extends AutoCloseable {
        /**
         * 添加put命令到管道
         */
        Pipeline put(Object key, Object value);

        /**
         * 添加带TTL的put命令到管道
         */
        Pipeline put(Object key, Object value, Duration ttl);

        /**
         * 添加get命令到管道
         */
        Pipeline get(Object key);

        /**
         * 添加evict命令到管道
         */
        Pipeline evict(Object key);

        /**
         * 添加exists命令到管道
         */
        Pipeline exists(Object key);

        /**
         * 执行管道中的所有命令
         *
         * @return 所有命令的执行结果
         */
        List<Object> execute();

        /**
         * 关闭管道
         */
        @Override
        void close();
    }

    /**
     * 事务操作接口
     */
    interface Transaction extends AutoCloseable {
        /**
         * 添加put命令到事务
         */
        Transaction put(Object key, Object value);

        /**
         * 添加带TTL的put命令到事务
         */
        Transaction put(Object key, Object value, Duration ttl);

        /**
         * 添加evict命令到事务
         */
        Transaction evict(Object key);

        /**
         * 监视键，如果键在事务执行前被修改，事务将被丢弃
         */
        Transaction watch(Object... keys);

        /**
         * 取消监视
         */
        Transaction unwatch();

        /**
         * 执行事务
         *
         * @return 事务执行结果，null表示事务被丢弃
         */
        List<Object> exec();

        /**
         * 丢弃事务
         */
        void discard();

        /**
         * 关闭事务
         */
        @Override
        void close();
    }

    // ==================== 发布订阅 ====================

    /**
     * 发布消息到指定频道
     *
     * @param channel 频道名称
     * @param message 消息内容
     * @return 接收到消息的订阅者数量
     */
    long publish(String channel, Object message);

    /**
     * 订阅频道
     *
     * @param listener 消息监听器
     * @param channels 频道名称
     * @return 订阅对象
     */
    Subscription subscribe(MessageListener listener, String... channels);

    /**
     * 模式订阅
     *
     * @param listener 消息监听器
     * @param patterns 频道模式
     * @return 订阅对象
     */
    Subscription psubscribe(MessageListener listener, String... patterns);

    /**
     * 消息监听器接口
     */
    @FunctionalInterface
    interface MessageListener {
        /**
         * 接收到消息时调用
         *
         * @param channel 频道名称
         * @param message 消息内容
         */
        void onMessage(String channel, Object message);
    }

    /**
     * 订阅对象接口
     */
    interface Subscription {
        /**
         * 取消订阅
         */
        void unsubscribe();

        /**
         * 获取订阅的频道数量
         */
        int getChannelCount();

        /**
         * 是否处于活跃状态
         */
        boolean isActive();
    }
}