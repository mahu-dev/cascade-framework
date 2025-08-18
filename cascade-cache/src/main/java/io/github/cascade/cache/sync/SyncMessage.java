package io.github.cascade.cache.sync;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 缓存同步消息
 *
 * @author cascade
 */
public class SyncMessage implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 消息类型
     */
    public enum Type {
        /** 缓存失效 */
        INVALIDATE,
        /** 批量失效 */
        INVALIDATE_ALL,
        /** 清空缓存 */
        CLEAR,
        /** 缓存更新 */
        UPDATE
    }
    
    /**
     * 消息ID
     */
    private String messageId;
    
    /**
     * 缓存名称
     */
    private String cacheName;
    
    /**
     * 消息类型
     */
    private Type type;
    
    /**
     * 缓存键
     */
    private Object key;
    
    /**
     * 缓存值（仅UPDATE类型使用）
     */
    private Object value;
    
    /**
     * 发送者ID（用于避免循环同步）
     */
    private String senderId;
    
    /**
     * 消息时间戳
     */
    private Instant timestamp;
    
    /**
     * 默认构造函数
     */
    public SyncMessage() {
        this.timestamp = Instant.now();
    }
    
    /**
     * 构造函数
     */
    public SyncMessage(String messageId, String cacheName, Type type, Object key, String senderId) {
        this();
        this.messageId = messageId;
        this.cacheName = cacheName;
        this.type = type;
        this.key = key;
        this.senderId = senderId;
    }
    
    /**
     * 构造函数（带值）
     */
    public SyncMessage(String messageId, String cacheName, Type type, Object key, Object value, String senderId) {
        this(messageId, cacheName, type, key, senderId);
        this.value = value;
    }
    
    // Getters and Setters
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getCacheName() {
        return cacheName;
    }
    
    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }
    
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        this.type = type;
    }
    
    public Object getKey() {
        return key;
    }
    
    public void setKey(Object key) {
        this.key = key;
    }
    
    public Object getValue() {
        return value;
    }
    
    public void setValue(Object value) {
        this.value = value;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncMessage that = (SyncMessage) o;
        return Objects.equals(messageId, that.messageId) &&
               Objects.equals(cacheName, that.cacheName) &&
               type == that.type &&
               Objects.equals(key, that.key) &&
               Objects.equals(senderId, that.senderId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(messageId, cacheName, type, key, senderId);
    }
    
    @Override
    public String toString() {
        return "SyncMessage{" +
               "messageId='" + messageId + '\'' +
               ", cacheName='" + cacheName + '\'' +
               ", type=" + type +
               ", key=" + key +
               ", senderId='" + senderId + '\'' +
               ", timestamp=" + timestamp +
               '}';
    }
}