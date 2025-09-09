package io.github.cascade.cache.simple;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Either模式接口 - 表示成功(Right)或失败(Left)的结果
 * <p>
 * Either模式是函数式编程中用于处理可能失败的计算的一种模式。
 * 它提供了一种类型安全的方式来表示两种可能的结果：成功(Right)或失败(Left)。
 *
 * @param <L> Left类型，通常表示错误或失败情况
 * @param <R> Right类型，通常表示成功情况的值
 * @author cascade
 */
public interface AbstractEither<L, R> {

    boolean isLeft();

    boolean isRight();

    L getLeft();

    R getRight();

    // 函数式操作
    <U> AbstractEither<L, U> map(Function<R, U> mapper);

    <U> AbstractEither<L, U> flatMap(Function<R, AbstractEither<L, U>> mapper);

    <U> AbstractEither<U, R> mapLeft(Function<L, U> mapper);

    AbstractEither<L, R> filter(Predicate<R> predicate, L leftValue);

    void forEach(Consumer<R> action);

    R getOrElse(R defaultValue);

    R getOrElse(Supplier<R> defaultSupplier);

    // 静态工厂方法
    static <L, R> AbstractEither<L, R> left(L value) {
        return new Left<>(value);
    }

    static <L, R> AbstractEither<L, R> right(R value) {
        return new Right<>(value);
    }

    static <L, R> AbstractEither<L, R> of(Supplier<R> supplier, Function<Exception, L> errorMapper) {
        try {
            return right(supplier.get());
        } catch (Exception e) {
            return left(errorMapper.apply(e));
        }
    }

    final class Left<L, R> implements AbstractEither<L, R> {
        private final L value;

        public Left(L value) {
            this.value = value;
        }

        @Override
        public boolean isLeft() {
            return true;
        }

        @Override
        public boolean isRight() {
            return false;
        }

        @Override
        public L getLeft() {
            return value;
        }

        @Override
        public R getRight() {
            throw new UnsupportedOperationException("Left value has no right");
        }

        @Override
        public <U> AbstractEither<L, U> map(Function<R, U> mapper) {
            return left(value);
        }

        @Override
        public <U> AbstractEither<L, U> flatMap(Function<R, AbstractEither<L, U>> mapper) {
            return left(value);
        }

        @Override
        public <U> AbstractEither<U, R> mapLeft(Function<L, U> mapper) {
            return left(mapper.apply(value));
        }

        @Override
        public AbstractEither<L, R> filter(Predicate<R> predicate, L leftValue) {
            return this;
        }

        @Override
        public void forEach(Consumer<R> action) {
            // Do nothing for Left
        }

        @Override
        public R getOrElse(R defaultValue) {
            return defaultValue;
        }

        @Override
        public R getOrElse(Supplier<R> defaultSupplier) {
            return defaultSupplier.get();
        }

        @Override
        public String toString() {
            return "Left(" + value + ")";
        }
    }

    final class Right<L, R> implements AbstractEither<L, R> {
        private final R value;

        public Right(R value) {
            this.value = value;
        }

        @Override
        public boolean isLeft() {
            return false;
        }

        @Override
        public boolean isRight() {
            return true;
        }

        @Override
        public L getLeft() {
            throw new UnsupportedOperationException("Right value has no left");
        }

        @Override
        public R getRight() {
            return value;
        }

        @Override
        public <U> AbstractEither<L, U> map(Function<R, U> mapper) {
            return right(mapper.apply(value));
        }

        @Override
        public <U> AbstractEither<L, U> flatMap(Function<R, AbstractEither<L, U>> mapper) {
            return mapper.apply(value);
        }

        @Override
        public <U> AbstractEither<U, R> mapLeft(Function<L, U> mapper) {
            return right(value);
        }

        @Override
        public AbstractEither<L, R> filter(Predicate<R> predicate, L leftValue) {
            return predicate.test(value) ? this : left(leftValue);
        }

        @Override
        public void forEach(Consumer<R> action) {
            action.accept(value);
        }

        @Override
        public R getOrElse(R defaultValue) {
            return value;
        }

        @Override
        public R getOrElse(Supplier<R> defaultSupplier) {
            return value;
        }

        @Override
        public String toString() {
            return "Right(" + value + ")";
        }
    }
}