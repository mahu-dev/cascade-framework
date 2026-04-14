package io.github.cascade.cache.v2.loader;

import java.util.function.Function;

/**
 * Loader优先级定义。
 * <p>
 * 用于在同名缓存已有loader时，决定新loader是否允许接管。
 */
public final class LoaderPriority {

    /**
     * 默认优先级（显式注册/自动发现/编程式传入loader）。
     */
    public static final int DEFAULT = 0;

    /**
     * 注解快照兜底loader优先级。
     */
    public static final int SNAPSHOT_FALLBACK = -100;

    private LoaderPriority() {
    }

    public static int of(Function<?, ?> loader) {
        if (loader instanceof Prioritized prioritized) {
            return prioritized.loaderPriority();
        }
        return DEFAULT;
    }

    /**
     * 可声明优先级的loader标记接口。
     */
    public interface Prioritized {
        int loaderPriority();
    }
}
