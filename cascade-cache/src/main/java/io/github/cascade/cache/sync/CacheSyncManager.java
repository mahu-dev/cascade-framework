package io.github.cascade.cache.sync;

/**
 * 缓存同步管理器接口
 */
public interface CacheSyncManager {

    /**
     * 发布缓存同步事件
     *
     * @param event 缓存同步事件
     */
    void publishEvent(CacheSyncEvent event);

    /**
     * 注册缓存同步监听器
     *
     * @param listener 监听器
     */
    void registerListener(CacheSyncListener listener);

    /**
     * 取消注册缓存同步监听器
     *
     * @param listenerId 监听器标识
     */
    void unregisterListener(String listenerId);

    /**
     * 获取当前节点ID
     *
     * @return 当前节点ID
     */
    String getCurrentNodeId();

    /**
     * 启动同步管理器
     */
    void start();

    /**
     * 停止同步管理器
     */
    void stop();

    /**
     * 获取同步状态
     *
     * @return 是否运行中
     */
    boolean isRunning();

    /**
     * 获取已注册的监听器数量
     *
     * @return 监听器数量
     */
    int getListenerCount();

    /**
     * 获取同步统计信息
     *
     * @return 统计信息
     */
    CacheSyncStats getStats();

    /**
     * 缓存同步统计
     */
    class CacheSyncStats {
        private long publishCount;
        private long receiveCount;
        private long errorCount;
        private long lastPublishTime;
        private long lastReceiveTime;

        public long getPublishCount() {
            return publishCount;
        }

        public void setPublishCount(long publishCount) {
            this.publishCount = publishCount;
        }

        public long getReceiveCount() {
            return receiveCount;
        }

        public void setReceiveCount(long receiveCount) {
            this.receiveCount = receiveCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public void setErrorCount(long errorCount) {
            this.errorCount = errorCount;
        }

        public long getLastPublishTime() {
            return lastPublishTime;
        }

        public void setLastPublishTime(long lastPublishTime) {
            this.lastPublishTime = lastPublishTime;
        }

        public long getLastReceiveTime() {
            return lastReceiveTime;
        }

        public void setLastReceiveTime(long lastReceiveTime) {
            this.lastReceiveTime = lastReceiveTime;
        }

        public void incrementPublishCount() {
            this.publishCount++;
            this.lastPublishTime = System.currentTimeMillis();
        }

        public void incrementReceiveCount() {
            this.receiveCount++;
            this.lastReceiveTime = System.currentTimeMillis();
        }

        public void incrementErrorCount() {
            this.errorCount++;
        }

        @Override
        public String toString() {
            return "CacheSyncStats{" +
                    "publishCount=" + publishCount +
                    ", receiveCount=" + receiveCount +
                    ", errorCount=" + errorCount +
                    ", lastPublishTime=" + lastPublishTime +
                    ", lastReceiveTime=" + lastReceiveTime +
                    '}';
        }
    }
}