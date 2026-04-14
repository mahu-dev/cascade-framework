package cc.coderm.cascade.idempotent.store;

import cc.coderm.cascade.idempotent.model.IdempotentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedissonIdempotentStoreTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    @SuppressWarnings("unchecked")
    private final RMap<String, String> recordMap = mock(RMap.class);

    private final Map<String, String> backing = new ConcurrentHashMap<>();
    private final AtomicBoolean lockHeld = new AtomicBoolean(false);

    private RedissonIdempotentStore store;

    @BeforeEach
    void setUp() throws Exception {
        backing.clear();
        lockHeld.set(false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(redissonClient.getMap(anyString(), any(StringCodec.class))).thenReturn((RMap) recordMap);

        when(lock.tryLock()).thenAnswer(invocation -> lockHeld.compareAndSet(false, true));
        when(lock.tryLock(any(Long.class), any(TimeUnit.class)))
                .thenAnswer(invocation -> lockHeld.compareAndSet(false, true));
        when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> lockHeld.get());
        doAnswer(invocation -> {
            lockHeld.set(false);
            return null;
        }).when(lock).unlock();

        when(recordMap.readAllMap()).thenAnswer(invocation -> new HashMap<>(backing));
        when(recordMap.get(anyString())).thenAnswer(invocation -> backing.get(invocation.getArgument(0, String.class)));
        when(recordMap.put(anyString(), anyString())).thenAnswer(invocation ->
                backing.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class)));
        doAnswer(invocation -> {
            Map<String, String> values = invocation.getArgument(0);
            backing.putAll(values);
            return null;
        }).when(recordMap).putAll(any(Map.class));
        when(recordMap.remove(anyString())).thenAnswer(invocation -> backing.remove(invocation.getArgument(0, String.class)));
        when(recordMap.expire(any(Duration.class))).thenReturn(true);

        store = new RedissonIdempotentStore(redissonClient);
    }

    @Test
    void shouldNotReOccupyAfterSucceededRecordExists() {
        String key = "idem:order:1";

        IdempotentStore.OccupyResult first = store.tryOccupy(key, "order", 30_000, "owner-1");
        assertThat(first.occupied()).isTrue();

        boolean committed = store.markSucceeded(key, "owner-1", "{\"ok\":true}", "java.lang.String", 30_000);
        assertThat(committed).isTrue();
        assertThat(backing.get("state")).isEqualTo(IdempotentState.SUCCEEDED.name());

        IdempotentStore.OccupyResult second = store.tryOccupy(key, "order", 30_000, "owner-2");

        assertThat(second.occupied()).isFalse();
        assertThat(second.existingRecord()).isNotNull();
        assertThat(second.existingRecord().getState()).isEqualTo(IdempotentState.SUCCEEDED);
        assertThat(backing.get("state")).isEqualTo(IdempotentState.SUCCEEDED.name());
        assertThat(backing.get("owner")).isNull();
    }

    @Test
    void shouldOccupyAfterTransientLockContentionWhenKeyNotExists() throws Exception {
        String key = "idem:order:2";
        AtomicInteger attempts = new AtomicInteger();
        when(lock.tryLock(any(Long.class), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    if (attempts.incrementAndGet() == 1) {
                        return false;
                    }
                    return lockHeld.compareAndSet(false, true);
                });

        IdempotentStore.OccupyResult result = store.tryOccupy(key, "order", 30_000, "owner-2");

        assertThat(result.occupied()).isTrue();
        assertThat(result.existingRecord()).isNull();
        assertThat(backing.get("state")).isEqualTo(IdempotentState.PROCESSING.name());
        assertThat(backing.get("owner")).isEqualTo("owner-2");
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldReturnContendedWhenLockTimesOutWithoutVisibleRecord() throws Exception {
        String key = "idem:order:3";
        when(lock.tryLock(any(Long.class), any(TimeUnit.class))).thenReturn(false);

        IdempotentStore.OccupyResult result = store.tryOccupy(key, "order", 30_000, "owner-3");

        assertThat(result.contended()).isTrue();
        assertThat(result.occupied()).isFalse();
        assertThat(result.conflicted()).isFalse();
        assertThat(result.existingRecord()).isNull();
    }
}
