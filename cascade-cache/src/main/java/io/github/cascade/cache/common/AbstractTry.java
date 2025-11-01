package io.github.cascade.cache.common;

import io.github.cascade.cache.common.exception.CacheException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;


public interface AbstractTry<T> {

    boolean isSuccess();

    boolean isFailure();

    T get();

    Exception getException();

    // 函数式操作
    <U> AbstractTry<U> map(Function<T, U> mapper);

    <U> AbstractTry<U> flatMap(Function<T, AbstractTry<U>> mapper);

    AbstractTry<T> recover(Function<Exception, T> recovery);

    AbstractTry<T> recoverWith(Function<Exception, AbstractTry<T>> recovery);

    AbstractTry<T> filter(Predicate<T> predicate);

    Optional<T> toOptional();


    // 带超时的操作
    default AbstractTry<T> timeout(Duration timeout) {
        if (this.isFailure()) {
            return this;
        }
        // 对于已经成功的值，直接返回
        return this;
    }

    // 静态工厂方法
    static <T> AbstractTry<T> success(T value) {
        return new Success<>(value);
    }

    static <T> AbstractTry<T> failure(Exception exception) {
        return new Failure<>(exception);
    }

    static <T> AbstractTry<T> of(Supplier<T> supplier) {
        try {
            return success(supplier.get());
        } catch (Exception e) {
            return failure(e);
        }
    }

    final class Success<T> implements AbstractTry<T> {
        private final T value;

        private Success(T value) {
            this.value = value;
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Exception getException() {
            throw new UnsupportedOperationException("Success has no exception");
        }

        @Override
        public <U> AbstractTry<U> map(Function<T, U> mapper) {
            return AbstractTry.of(() -> mapper.apply(value));
        }

        @Override
        public <U> AbstractTry<U> flatMap(Function<T, AbstractTry<U>> mapper) {
            try {
                return mapper.apply(value);
            } catch (Exception e) {
                return failure(e);
            }
        }

        @Override
        public AbstractTry<T> recover(Function<Exception, T> recovery) {
            return this;
        }

        @Override
        public AbstractTry<T> recoverWith(Function<Exception, AbstractTry<T>> recovery) {
            return this;
        }

        @Override
        public AbstractTry<T> filter(Predicate<T> predicate) {
            try {
                return predicate.test(value) ? this :
                        failure(new IllegalArgumentException("Predicate failed"));
            } catch (Exception e) {
                return failure(e);
            }
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.of(value);
        }


        @Override
        public String toString() {
            return "Success(" + value + ")";
        }
    }

    final class Failure<T> implements AbstractTry<T> {
        private final Exception exception;

        private Failure(Exception exception) {
            this.exception = Objects.requireNonNull(exception);
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T get() {
            throw new CacheException("Try failed", exception);
        }

        @Override
        public Exception getException() {
            return exception;
        }

        @Override
        public <U> AbstractTry<U> map(Function<T, U> mapper) {
            return failure(exception);
        }

        @Override
        public <U> AbstractTry<U> flatMap(Function<T, AbstractTry<U>> mapper) {
            return failure(exception);
        }

        @Override
        public AbstractTry<T> recover(Function<Exception, T> recovery) {
            return AbstractTry.of(() -> recovery.apply(exception));
        }

        @Override
        public AbstractTry<T> recoverWith(Function<Exception, AbstractTry<T>> recovery) {
            try {
                return recovery.apply(exception);
            } catch (Exception e) {
                return failure(e);
            }
        }

        @Override
        public AbstractTry<T> filter(Predicate<T> predicate) {
            return this;
        }

        @Override
        public Optional<T> toOptional() {
            return Optional.empty();
        }


        @Override
        public String toString() {
            return "Failure(" + exception.getMessage() + ")";
        }
    }
}