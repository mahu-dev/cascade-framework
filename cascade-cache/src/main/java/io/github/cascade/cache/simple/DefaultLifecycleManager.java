package io.github.cascade.cache.simple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认生命周期管理器实现
 * 支持多种类型的组件生命周期管理
 *
 * @author cascade
 */
//@Component
public class DefaultLifecycleManager implements LifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultLifecycleManager.class);

    /**
     * 运行中的组件存储
     * Key: 组件名称, Value: 组件实例
     */
    private final ConcurrentMap<String, Object> runningComponents = new ConcurrentHashMap<>();

    /**
     * 管理器是否已关闭
     */
    private volatile boolean closed = false;

    @Override
    public <T> void start(String name, T component) {
        checkNotClosed();

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("组件名称不能为空");
        }
        if (component == null) {
            throw new IllegalArgumentException("组件实例不能为空");
        }

        // 检查是否已存在同名组件
        Object existing = runningComponents.putIfAbsent(name, component);
        if (existing != null) {
            log.warn("组件已存在，启动失败: name={}, existingType={}", name, existing.getClass().getSimpleName());
            return;
        }

        try {
            // 尝试启动组件
            startComponent(name, component);
            log.info("组件启动成功: name={}, type={}", name, component.getClass().getSimpleName());
        } catch (Exception e) {
            // 启动失败时从运行列表中移除
            runningComponents.remove(name);
            log.error("组件启动失败: name={}, error={}", name, e.getMessage(), e);
            throw new RuntimeException("组件启动失败: " + name, e);
        }
    }

    @Override
    public boolean stop(String name) {
        checkNotClosed();

        if (name == null || name.trim().isEmpty()) {
            return false;
        }

        Object component = runningComponents.remove(name);
        if (component == null) {
            log.debug("组件不存在或已停止: name={}", name);
            return false;
        }

        try {
            stopComponent(name, component);
            log.info("组件停止成功: name={}, type={}", name, component.getClass().getSimpleName());
            return true;
        } catch (Exception e) {
            log.error("组件停止失败: name={}, error={}", name, e.getMessage());
            return false;
        }
    }

    @Override
    public void stopAll() {
        if (runningComponents.isEmpty()) {
            log.debug("没有运行中的组件需要停止");
            return;
        }

        int count = runningComponents.size();
        log.info("开始停止所有组件，总数: {}", count);

        // 创建名称副本避免ConcurrentModificationException
        String[] names = runningComponents.keySet().toArray(new String[0]);
        int successCount = 0;

        for (String name : names) {
            try {
                if (stop(name)) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("停止组件异常: name={}, error={}", name, e.getMessage());
            }
        }

        log.info("所有组件停止完成: 总数={}, 成功={}, 失败={}", count, successCount, count - successCount);
    }

    @Override
    public boolean isRunning(String name) {
        return !closed && name != null && runningComponents.containsKey(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getComponent(String name) {
        if (name == null || closed) {
            return null;
        }
        Object component = runningComponents.get(name);
        return component != null ? (T) component : null;
    }

    @Override
    public Collection<String> getRunningComponentNames() {
        return runningComponents.keySet();
    }

    @Override
    public int getRunningCount() {
        return runningComponents.size();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            log.debug("生命周期管理器已关闭，无需重复关闭");
            return;
        }

        log.info("开始关闭生命周期管理器...");
        closed = true;

        // 停止所有组件
        stopAll();

        log.info("生命周期管理器关闭完成");
    }

    /**
     * 启动具体组件的逻辑
     *
     * @param name      组件名称
     * @param component 组件实例
     */
    private void startComponent(String name, Object component) {
        // 根据组件类型执行相应的启动逻辑
        if (component instanceof CacheRefresher<?, ?> refresher) {
            refresher.start();
            log.debug("缓存刷新器已启动: name={}", name);
        } else if (component instanceof CacheSync<?, ?> sync) {
            sync.start();
            log.debug("缓存同步器已启动: name={}", name);
        } else if (component instanceof Cache) {
            // 缓存实例一般不需要特殊启动逻辑
            log.debug("缓存实例已注册: name={}", name);
        } else {
            log.debug("通用组件已启动: name={}, type={}", name, component.getClass().getSimpleName());
        }
    }

    /**
     * 停止具体组件的逻辑
     *
     * @param name      组件名称
     * @param component 组件实例
     */
    private void stopComponent(String name, Object component) {
        // 根据组件类型执行相应的停止逻辑
        if (component instanceof CacheRefresher<?, ?> refresher) {
            refresher.stop();
            log.debug("缓存刷新器已停止: name={}", name);
        } else if (component instanceof CacheSync<?, ?> sync) {
            sync.stop();
            log.debug("缓存同步器已停止: name={}", name);
        } else if (component instanceof Cache<?, ?> cache) {
            if (!cache.isClosed()) {
                cache.close();
            }
            log.debug("缓存实例已关闭: name={}", name);
        } else if (component instanceof AutoCloseable autoCloseable) {
            try {
                autoCloseable.close();
                log.debug("自动关闭组件: name={}", name);
            } catch (Exception e) {
                throw new RuntimeException("关闭组件失败: " + name, e);
            }
        } else {
            log.debug("通用组件已停止: name={}, type={}", name, component.getClass().getSimpleName());
        }
    }

    /**
     * 检查管理器是否未关闭
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("生命周期管理器已关闭");
        }
    }

    /**
     * 获取统计信息
     *
     * @return 统计信息字符串
     */
    public String getStatsString() {
        return String.format("DefaultLifecycleManager{runningCount=%d, closed=%s, components=%s}",
                getRunningCount(), closed, getRunningComponentNames());
    }

    @Override
    public String toString() {
        return getStatsString();
    }
}