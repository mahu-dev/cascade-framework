package cc.coderm.cascade.bloom;

import cc.coderm.cascade.bloom.util.BloomFilterResultPresenceUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BloomFilterResultPresenceUtil 异步结果判定测试")
class BloomFilterResultPresenceUtilTest {

    @Test
    @DisplayName("shouldWriteBack - CompletableFuture 容器本身不应判定为可写回")
    void shouldNotTreatCompletableFutureContainerAsPresent() {
        CompletableFuture<String> completedFuture = CompletableFuture.completedFuture("value");

        assertThat(BloomFilterResultPresenceUtil.shouldWriteBack(completedFuture)).isFalse();
    }

    @Test
    @DisplayName("toCompletionFuture - 普通值应包装为已完成 future")
    void shouldWrapSyncValueToCompletedFuture() {
        Object value = "payload";

        Object resolved = BloomFilterResultPresenceUtil.toCompletionFuture(value).join();

        assertThat(resolved).isEqualTo("payload");
    }

    @Test
    @DisplayName("toCompletionFuture - CompletionStage 应被扁平化为真实 payload")
    void shouldFlattenCompletionStageValue() {
        CompletableFuture<String> stage = CompletableFuture.completedFuture("async-payload");

        Object resolved = BloomFilterResultPresenceUtil.toCompletionFuture(stage).join();

        assertThat(resolved).isEqualTo("async-payload");
    }
}

