package cc.coderm.cascade.idempotent;

import cc.coderm.cascade.idempotent.annotation.Idempotent;
import cc.coderm.cascade.idempotent.config.CascadeIdempotentProperties;
import cc.coderm.cascade.idempotent.exception.IdempotentConflictException;
import cc.coderm.cascade.idempotent.executor.IdempotentExecutor;
import cc.coderm.cascade.idempotent.model.ConflictStrategy;
import cc.coderm.cascade.idempotent.model.IdempotentContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 幂等编程式门面。
 *
 * <p>通过链式 Builder 构建幂等执行配置，屏蔽 {@link IdempotentContext} 的样板代码。
 */
public class IdempotentTemplate {

    private static final String DEFAULT_SCENE = annotationDefault("scene", String.class);
    private static final long DEFAULT_TTL = annotationDefault("ttl", Long.class);
    private static final TimeUnit DEFAULT_TTL_UNIT = annotationDefault("ttlUnit", TimeUnit.class);
    private static final boolean DEFAULT_DELETE_ON_SUCCESS = annotationDefault("deleteOnSuccess", Boolean.class);
    private static final boolean DEFAULT_DELETE_ON_FAILURE = annotationDefault("deleteOnFailure", Boolean.class);
    private static final ConflictStrategy DEFAULT_CONFLICT_STRATEGY =
            annotationDefault("conflictStrategy", ConflictStrategy.class);
    private static final long DEFAULT_WAIT_TIMEOUT_MS = annotationDefault("waitTimeoutMs", Long.class);

    private final IdempotentExecutor executor;
    private final CascadeIdempotentProperties properties;

    public IdempotentTemplate(IdempotentExecutor executor,
                              CascadeIdempotentProperties properties) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 创建一个编程式幂等 Builder。
     *
     * @param key 业务幂等 key（不含统一前缀和 scene）
     */
    public Builder key(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Idempotent key must not be blank");
        }
        return new Builder(key.trim());
    }

    public final class Builder {
        private final String rawKey;
        private final Thread ownerThread = Thread.currentThread();
        private String scene = DEFAULT_SCENE;
        private long ttl = DEFAULT_TTL;
        private TimeUnit ttlUnit = DEFAULT_TTL_UNIT;
        private boolean deleteOnSuccess = DEFAULT_DELETE_ON_SUCCESS;
        private boolean deleteOnFailure = DEFAULT_DELETE_ON_FAILURE;
        private ConflictStrategy conflictStrategy = DEFAULT_CONFLICT_STRATEGY;
        private long waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS;

        private Builder(String rawKey) {
            this.rawKey = rawKey;
        }

        public Builder scene(String scene) {
            assertThreadBound();
            if (!StringUtils.hasText(scene)) {
                throw new IllegalArgumentException("scene must not be blank");
            }
            this.scene = scene.trim();
            return this;
        }

        public Builder ttl(long ttl, TimeUnit ttlUnit) {
            assertThreadBound();
            if (ttl <= 0) {
                throw new IllegalArgumentException("ttl must be > 0");
            }
            this.ttl = ttl;
            this.ttlUnit = Objects.requireNonNull(ttlUnit, "ttlUnit");
            return this;
        }

        public Builder deleteOnSuccess(boolean deleteOnSuccess) {
            assertThreadBound();
            this.deleteOnSuccess = deleteOnSuccess;
            return this;
        }

        public Builder deleteOnFailure(boolean deleteOnFailure) {
            assertThreadBound();
            this.deleteOnFailure = deleteOnFailure;
            return this;
        }

        public Builder conflictStrategy(ConflictStrategy conflictStrategy) {
            assertThreadBound();
            this.conflictStrategy = Objects.requireNonNull(conflictStrategy, "conflictStrategy");
            return this;
        }

        public Builder waitTimeoutMs(long waitTimeoutMs) {
            assertThreadBound();
            if (waitTimeoutMs < 0) {
                throw new IllegalArgumentException("waitTimeoutMs must be >= 0");
            }
            this.waitTimeoutMs = waitTimeoutMs;
            return this;
        }

        public <T, E extends Exception> T execute(Class<T> returnType,
                                                  ThrowingSupplier<T, E> action) throws E {
            return execute((Type) Objects.requireNonNull(returnType, "returnType"), action, null);
        }

        public <T, E extends Exception> T execute(Class<T> returnType,
                                                  ThrowingSupplier<T, E> action,
                                                  ThrowingSupplier<T, E> conflictFallback) throws E {
            return execute((Type) Objects.requireNonNull(returnType, "returnType"), action, conflictFallback);
        }

        public <T, E extends Exception> T execute(ParameterizedTypeReference<T> returnType,
                                                  ThrowingSupplier<T, E> action) throws E {
            Objects.requireNonNull(returnType, "returnType");
            return execute(returnType.getType(), action, null);
        }

        public <T, E extends Exception> T execute(ParameterizedTypeReference<T> returnType,
                                                  ThrowingSupplier<T, E> action,
                                                  ThrowingSupplier<T, E> conflictFallback) throws E {
            Objects.requireNonNull(returnType, "returnType");
            return execute(returnType.getType(), action, conflictFallback);
        }

        public <E extends Exception> void run(ThrowingRunnable<E> action) throws E {
            Objects.requireNonNull(action, "action");
            execute(Void.class, () -> {
                action.run();
                return null;
            });
        }

        public <E extends Exception> void run(ThrowingRunnable<E> action,
                                              ThrowingRunnable<E> conflictFallback) throws E {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(conflictFallback, "conflictFallback");
            execute(Void.class, () -> {
                action.run();
                return null;
            }, () -> {
                conflictFallback.run();
                return null;
            });
        }

        private <T, E extends Exception> T execute(Type returnType,
                                                   ThrowingSupplier<T, E> action,
                                                   ThrowingSupplier<T, E> conflictFallback) throws E {
            assertThreadBound();
            Objects.requireNonNull(action, "action");
            IdempotentContext context = buildContext(returnType);
            try {
                Object result = executor.execute(context, () -> invokeBusiness(action));
                @SuppressWarnings("unchecked")
                T casted = (T) result;
                return casted;
            } catch (BusinessCallbackException ex) {
                @SuppressWarnings("unchecked")
                E businessEx = (E) ex.getBusinessException();
                throw businessEx;
            } catch (IdempotentConflictException ex) {
                if (conflictFallback != null) {
                    return conflictFallback.get();
                }
                throw ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting idempotent completion.", ex);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Error ex) {
                throw ex;
            } catch (Throwable ex) {
                throw new IllegalStateException(
                        "Unexpected checked throwable from idempotent executor: " + ex.getClass().getName(),
                        ex);
            }
        }

        private <T, E extends Exception> T invokeBusiness(ThrowingSupplier<T, E> action)
                throws BusinessCallbackException {
            try {
                return action.get();
            } catch (Exception ex) {
                throw new BusinessCallbackException(ex);
            }
        }

        private IdempotentContext buildContext(Type returnType) {
            Objects.requireNonNull(returnType, "returnType");
            long ttlMs = ttlUnit.toMillis(ttl);
            if (ttlMs <= 0) {
                throw new IllegalArgumentException("ttl converted to milliseconds must be > 0");
            }
            return IdempotentContext.builder()
                    .idempotentKey(buildFullKey())
                    .ttlMs(ttlMs)
                    .scene(scene)
                    .deleteOnSuccess(deleteOnSuccess)
                    .deleteOnFailure(deleteOnFailure)
                    .conflictStrategy(conflictStrategy)
                    .waitTimeoutMs(waitTimeoutMs)
                    .targetMethod(null)
                    .returnType(returnType)
                    .build();
        }

        private String buildFullKey() {
            return properties.getRedisKeyPrefix() + scene + ":" + rawKey;
        }

        private void assertThreadBound() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                        "IdempotentTemplate.Builder is thread-confined and must not be shared across threads");
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    private static final class BusinessCallbackException extends Exception {
        private final Exception businessException;

        private BusinessCallbackException(Exception businessException) {
            super(businessException);
            this.businessException = businessException;
        }

        private Exception getBusinessException() {
            return businessException;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T annotationDefault(String attributeName, Class<T> expectedType) {
        try {
            Method method = Idempotent.class.getDeclaredMethod(attributeName);
            Object value = method.getDefaultValue();
            if (value == null) {
                throw new IllegalStateException("Missing @Idempotent default value for attribute: " + attributeName);
            }
            if (!expectedType.isInstance(value)) {
                throw new IllegalStateException("Unexpected @Idempotent default type for attribute '" + attributeName
                        + "', expected=" + expectedType.getName()
                        + ", actual=" + value.getClass().getName());
            }
            return (T) value;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Unknown @Idempotent attribute: " + attributeName, ex);
        }
    }
}
