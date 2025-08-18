package io.github.cascade.cache.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Pub/Sub缓存同步器实现
 *
 * @author cascade
 */
public class RedisPubSubSynchronizer implements MessageListener {
    
    private static final String DEFAULT_CHANNEL = "cascade:cache:sync";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final String synchronizerId;
    private final String channel;
    
    private final Set<SyncMessageListener> messageListeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sentCount = new AtomicLong(0);
    private final AtomicLong receivedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    
    private ScheduledExecutorService executorService;
    private SynchronizerConfig config;
    
    /**
     * 构造函数
     */
    public RedisPubSubSynchronizer(RedisTemplate<String, Object> redisTemplate,
                                   RedisMessageListenerContainer listenerContainer) {
        this(redisTemplate, listenerContainer, DEFAULT_CHANNEL);
    }
    
    /**
     * 构造函数
     */
    public RedisPubSubSynchronizer(RedisTemplate<String, Object> redisTemplate,
                                   RedisMessageListenerContainer listenerContainer,
                                   String channel) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.channel = channel;
        this.synchronizerId = UUID.randomUUID().toString();
        this.objectMapper = new ObjectMapper();
        this.config = new SynchronizerConfig();
    }
    
    /**
     * 启动同步器
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            executorService = Executors.newScheduledThreadPool(2);
            
            // 订阅Redis频道
            listenerContainer.addMessageListener(this, new ChannelTopic(channel));
            
            if (!listenerContainer.isRunning()) {
                listenerContainer.start();
            }
        }
    }
    
    /**
     * 停止同步器
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            // 取消订阅
            listenerContainer.removeMessageListener(this, new ChannelTopic(channel));
            
            // 关闭线程池
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * 发送同步消息
     */
    public CompletableFuture<Void> sendMessage(SyncMessage message) {
        if (!running.get() || !config.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // 设置发送者ID
        message.setSenderId(synchronizerId);
        
        if (config.isAsyncSend()) {
            return CompletableFuture.runAsync(() -> doSendMessage(message), executorService);
        } else {
            try {
                doSendMessage(message);
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        }
    }
    
    /**
     * 实际发送消息
     */
    private void doSendMessage(SyncMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);
            sentCount.incrementAndGet();
            
            // 通知监听器发送成功
            messageListeners.forEach(listener -> {
                try {
                    listener.onMessageSent(message);
                } catch (Exception e) {
                    // 忽略监听器异常
                }
            });
            
        } catch (JsonProcessingException e) {
            failedCount.incrementAndGet();
            
            // 通知监听器发送失败
            messageListeners.forEach(listener -> {
                try {
                    listener.onMessageSendFailed(message, e);
                } catch (Exception ex) {
                    // 忽略监听器异常
                }
            });
            
            throw new RuntimeException("Failed to send sync message", e);
        }
    }
    
    /**
     * 处理接收到的Redis消息
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (!running.get()) {
            return;
        }
        
        try {
            String json = new String(message.getBody());
            SyncMessage syncMessage = objectMapper.readValue(json, SyncMessage.class);
            
            // 避免处理自己发送的消息
            if (synchronizerId.equals(syncMessage.getSenderId())) {
                return;
            }
            
            receivedCount.incrementAndGet();
            
            // 通知所有监听器
            messageListeners.forEach(listener -> {
                try {
                    if (listener.shouldHandle(syncMessage)) {
                        listener.onMessage(syncMessage);
                    }
                } catch (Exception e) {
                    // 记录但不抛出异常，避免影响其他监听器
                    System.err.println("Error processing sync message in listener " + 
                                     listener.getName() + ": " + e.getMessage());
                }
            });
            
        } catch (JsonProcessingException e) {
            System.err.println("Failed to parse sync message: " + e.getMessage());
        }
    }
    
    /**
     * 添加消息监听器
     */
    public void addMessageListener(SyncMessageListener listener) {
        messageListeners.add(listener);
    }
    
    /**
     * 移除消息监听器
     */
    public void removeMessageListener(SyncMessageListener listener) {
        messageListeners.remove(listener);
    }
    
    /**
     * 获取所有监听器
     */
    public Set<SyncMessageListener> getMessageListeners() {
        return Set.copyOf(messageListeners);
    }
    
    /**
     * 获取同步器ID
     */
    public String getSynchronizerId() {
        return synchronizerId;
    }
    
    /**
     * 获取配置
     */
    public SynchronizerConfig getConfig() {
        return config;
    }
    
    /**
     * 设置配置
     */
    public void setConfig(SynchronizerConfig config) {
        this.config = config;
    }
    
    /**
     * 获取统计信息
     */
    public SyncStats getStats() {
        return new SyncStats() {
            @Override
            public long getSentMessageCount() {
                return sentCount.get();
            }
            
            @Override
            public long getReceivedMessageCount() {
                return receivedCount.get();
            }
            
            @Override
            public long getFailedMessageCount() {
                return failedCount.get();
            }
            
            @Override
            public void reset() {
                sentCount.set(0);
                receivedCount.set(0);
                failedCount.set(0);
            }
        };
    }
    
    /**
     * 同步器配置
     */
    public static class SynchronizerConfig {
        private boolean enabled = true;
        private long timeoutMs = 5000;
        private int retryCount = 3;
        private boolean asyncSend = true;
        
        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public long getTimeoutMs() {
            return timeoutMs;
        }
        
        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
        
        public boolean isAsyncSend() {
            return asyncSend;
        }
        
        public void setAsyncSend(boolean asyncSend) {
            this.asyncSend = asyncSend;
        }
    }
    
    /**
     * 同步统计信息接口
     */
    public interface SyncStats {
        long getSentMessageCount();
        long getReceivedMessageCount();
        long getFailedMessageCount();
        void reset();
    }
}