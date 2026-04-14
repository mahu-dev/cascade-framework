package io.github.cascade.lock.util;

/**
 * 统一的受检异常绕过工具，避免在多个类中重复实现 sneaky-throw。
 */
public final class SneakyThrow {

    private SneakyThrow() {
    }

    @SuppressWarnings("unchecked")
    public static <T, E extends Throwable> T rethrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
