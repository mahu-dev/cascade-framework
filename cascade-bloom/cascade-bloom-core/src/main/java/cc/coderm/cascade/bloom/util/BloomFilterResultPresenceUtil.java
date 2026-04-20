package cc.coderm.cascade.bloom.util;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 布隆过滤器写回场景的“结果存在性”判定工具。
 * <p>
 * 语义约定：
 * <ul>
 *   <li>{@code null}：视为不存在，不写回</li>
 *   <li>{@link Optional#empty()}：视为不存在，不写回</li>
 *   <li>{@link OptionalInt#empty()} / {@link OptionalLong#empty()} / {@link OptionalDouble#empty()}：视为不存在，不写回</li>
 *   <li>{@link CompletionStage}：异步结果占位对象，不在此处判定存在性（应在异步完成后基于真实值判定）</li>
 *   <li>其余非 null 值：视为存在，可写回</li>
 * </ul>
 */
public final class BloomFilterResultPresenceUtil {

    private BloomFilterResultPresenceUtil() {
    }

    /**
     * 判断业务返回值是否表示“源数据存在”，从而决定是否允许回填布隆过滤器。
     *
     * @param result 业务返回值
     * @return {@code true} 表示可回填；{@code false} 表示不可回填
     */
    public static boolean shouldWriteBack(Object result) {
        if (result == null) {
            return false;
        }
        if (isAsyncResult(result)) {
            // CompletionStage 仅是异步容器，不代表业务数据存在；
            // 需要在异步完成后对真实 payload 再做 shouldWriteBack 判定。
            return false;
        }
        if (result instanceof Optional<?> optional) {
            return optional.isPresent();
        }
        if (result instanceof OptionalInt optionalInt) {
            return optionalInt.isPresent();
        }
        if (result instanceof OptionalLong optionalLong) {
            return optionalLong.isPresent();
        }
        if (result instanceof OptionalDouble optionalDouble) {
            return optionalDouble.isPresent();
        }
        return true;
    }

    /**
     * 是否为异步返回值容器（CompletionStage）。
     */
    public static boolean isAsyncResult(Object result) {
        return result instanceof CompletionStage<?>;
    }

    /**
     * 将普通值或 CompletionStage 统一桥接为 CompletableFuture，便于异步链路组合。
     */
    public static CompletableFuture<Object> toCompletionFuture(Object result) {
        if (result instanceof CompletionStage<?> completionStage) {
            return completionStage.toCompletableFuture().thenApply(value -> value);
        }
        return CompletableFuture.completedFuture(result);
    }
}
