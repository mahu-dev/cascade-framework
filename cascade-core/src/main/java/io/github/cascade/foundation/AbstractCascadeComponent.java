package io.github.cascade.foundation;

import io.github.cascade.api.CascadeComponent;
import io.github.cascade.api.HealthStatus;
import io.github.cascade.api.Lifecycle;
import io.github.cascade.event.CascadeEvent;
import io.github.cascade.event.EventListener;
import io.github.cascade.metrics.ComponentMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cascade组件基础抽象类
 */
public abstract class AbstractCascadeComponent implements CascadeComponent {

    protected final String id;
    protected final String name;
    protected final AtomicReference<Lifecycle.State> state = new AtomicReference<>(Lifecycle.State.NEW);
    protected final Map<Class<?>, CopyOnWriteArrayList<EventListener<?>>> eventListeners = new ConcurrentHashMap<>();

    protected AbstractCascadeComponent(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Lifecycle.State getState() {
        return state.get();
    }

    @Override
    public void initialize() throws Exception {
        if (state.compareAndSet(Lifecycle.State.NEW, Lifecycle.State.INITIALIZING)) {
            try {
                doInitialize();
                state.set(Lifecycle.State.INITIALIZED);
            } catch (Exception e) {
                state.set(Lifecycle.State.FAILED);
                throw e;
            }
        }
    }

    @Override
    public void start() throws Exception {
        Lifecycle.State currentState = state.get();
        if (currentState == Lifecycle.State.NEW) {
            initialize();
        }

        if (state.compareAndSet(Lifecycle.State.INITIALIZED, Lifecycle.State.STARTING)) {
            try {
                doStart();
                state.set(Lifecycle.State.STARTED);
            } catch (Exception e) {
                state.set(Lifecycle.State.FAILED);
                throw e;
            }
        }
    }

    @Override
    public void stop() throws Exception {
        if (state.compareAndSet(Lifecycle.State.STARTED, Lifecycle.State.STOPPING)) {
            try {
                doStop();
                state.set(Lifecycle.State.STOPPED);
            } catch (Exception e) {
                state.set(Lifecycle.State.FAILED);
                throw e;
            }
        }
    }

    @Override
    public void close() {
        try {
            if (state.get() == Lifecycle.State.STARTED) {
                stop();
            }
            doClose();
        } catch (Exception e) {
            // 忽略关闭异常
        }
    }

    @Override
    public HealthStatus health() {
        HealthStatus.Status status = switch (getState()) {
            case STARTED -> HealthStatus.Status.UP;
            case FAILED -> HealthStatus.Status.DOWN;
            case STOPPED -> HealthStatus.Status.OUT_OF_SERVICE;
            default -> HealthStatus.Status.UNKNOWN;
        };

        return HealthStatus.builder()
                .withDetail("state", getState().name())
                .withDetail("id", getId())
                .withDetail("name", getName())
                .withDetail("type", getType().name())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends CascadeEvent> void addEventListener(Class<E> eventType, EventListener<E> listener) {
        eventListeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends CascadeEvent> void removeEventListener(Class<E> eventType, EventListener<E> listener) {
        CopyOnWriteArrayList<EventListener<?>> listeners = eventListeners.get(eventType);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void publishEvent(CascadeEvent event) {
        CopyOnWriteArrayList<EventListener<?>> listeners = eventListeners.get(event.getClass());
        if (listeners != null) {
            for (EventListener<?> listener : listeners) {
                try {
                    ((EventListener<CascadeEvent>) listener).onEvent(event);
                } catch (Exception e) {
                    // 记录日志但不中断处理
                    System.err.println("Event listener error: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public ComponentMetrics getMetrics() {
        return new DefaultComponentMetrics(getName());
    }

    // 子类需要实现的生命周期方法
    protected abstract void doInitialize() throws Exception;

    protected abstract void doStart() throws Exception;

    protected abstract void doStop() throws Exception;

    protected abstract void doClose();

    /**
     * 默认组件指标实现
     */
    private static class DefaultComponentMetrics implements ComponentMetrics {
        private final String componentName;

        public DefaultComponentMetrics(String componentName) {
            this.componentName = componentName;
        }

        @Override
        public String getComponentName() {
            return componentName;
        }

        @Override
        public Map<String, Object> getMetrics() {
            Map<String, Object> metrics = new ConcurrentHashMap<>();
            metrics.put("timestamp", System.currentTimeMillis());
            return metrics;
        }

        @Override
        public void reset() {
            // 默认实现为空
        }
    }
}