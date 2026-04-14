package io.github.cascade.lock.factory;

import io.github.cascade.lock.enums.LockType;
import io.github.cascade.lock.exception.LockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LockFactoryTest {

    @Mock
    private RedissonClient redissonClient;

    private LockFactory lockFactory;

    @BeforeEach
    void setUp() {
        lockFactory = new LockFactory(redissonClient);
    }

    @Test
    void shouldFailFastWhenSingleKeyLockTypeIsNull() {
        LockException ex = assertThrows(
                LockException.class,
                () -> lockFactory.getLock((LockType) null, "order:1")
        );
        assertEquals("lockType 不能为空", ex.getMessage());
        assertEquals("order:1", ex.getLockKey());
        verifyNoInteractions(redissonClient);
    }

    @Test
    void shouldFailFastWhenMultiKeyLockTypeIsNull() {
        List<String> keys = List.of("order:1", "order:2");
        LockException ex = assertThrows(
                LockException.class,
                () -> lockFactory.getMultiLock((LockType) null, keys)
        );
        assertEquals("lockType 不能为空", ex.getMessage());
        assertEquals(keys.toString(), ex.getLockKey());
        verifyNoInteractions(redissonClient);
    }
}
