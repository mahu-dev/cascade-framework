package io.github.cascade.cache.v2.facade;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 注解缓存路径的方法调用快照支持。
 * <p>
 * 负责：
 * 1. 保存可用于自动刷新回放的方法调用上下文；
 * 2. 管理内部回放深度，避免切面递归；
 * 3. 提供快照级别的删除与清空能力。
 */
public class CacheInvocationSnapshotSupport {

    static final int SNAPSHOT_MAX_SIZE = 10_000;
    static final long SNAPSHOT_EXPIRE_AFTER_ACCESS_MINUTES = 30L;

    private final com.github.benmanes.caffeine.cache.Cache<SnapshotKey, InvocationSnapshot> invocationSnapshots = Caffeine
            .newBuilder()
            .maximumSize(SNAPSHOT_MAX_SIZE)
            .expireAfterAccess(SNAPSHOT_EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
            .build();

    private final ThreadLocal<Integer> internalInvocationDepth = ThreadLocal.withInitial(() -> 0);

    public void register(String cacheName, Object cacheKey, JoinPoint joinPoint) {
        if (cacheKey == null || joinPoint == null) {
            return;
        }
        // 使用 getThis() 获取代理对象，而非 getTarget() 的原始对象，
        // 确保回放时 Method.invoke 经过 Spring 代理链，@Transactional 等切面正常生效。
        Object proxy = joinPoint.getThis();
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        InvocationSnapshot snapshot = new InvocationSnapshot(proxy, method, joinPoint.getArgs());
        invocationSnapshots.put(new SnapshotKey(cacheName, cacheKey), snapshot);
    }

    public InvocationSnapshot getSnapshot(String cacheName, Object cacheKey) {
        return invocationSnapshots.getIfPresent(new SnapshotKey(cacheName, cacheKey));
    }

    public Object invoke(InvocationSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            enterInternalInvocation();
            return snapshot.method().invoke(snapshot.target(), snapshot.args());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw new RuntimeException(cause != null ? cause : e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            exitInternalInvocation();
        }
    }

    public boolean isInternalInvocation() {
        return internalInvocationDepth.get() > 0;
    }

    public void remove(String cacheName, Object cacheKey) {
        if (cacheName == null || cacheKey == null) {
            return;
        }
        invocationSnapshots.invalidate(new SnapshotKey(cacheName, cacheKey));
    }

    public void clear(String cacheName) {
        if (!StringUtils.hasText(cacheName)) {
            return;
        }
        invocationSnapshots.asMap().keySet().removeIf(key -> Objects.equals(key.cacheName(), cacheName));
    }

    public long snapshotCount() {
        return invocationSnapshots.estimatedSize();
    }

    private void enterInternalInvocation() {
        internalInvocationDepth.set(internalInvocationDepth.get() + 1);
    }

    private void exitInternalInvocation() {
        int depth = internalInvocationDepth.get() - 1;
        if (depth <= 0) {
            internalInvocationDepth.remove();
            return;
        }
        internalInvocationDepth.set(depth);
    }

    record SnapshotKey(String cacheName, Object cacheKey) {
    }

    /**
     * 方法调用快照，用于缓存自动刷新时的回放。
     * <p>
     * 保存方法调用的完整上下文（目标对象、方法、参数），
     * 使缓存刷新时能够重新执行原始方法获取最新数据。
     *
     * @param target 目标对象，方法被调用的实例
     * @param method 方法对象，通过反射执行调用
     * @param args   方法参数，用于回放时传递给方法
     */
    public record InvocationSnapshot(Object target, Method method, Object[] args) {
    }
}
