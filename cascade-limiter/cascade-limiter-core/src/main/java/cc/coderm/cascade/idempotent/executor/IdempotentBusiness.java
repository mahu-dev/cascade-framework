package cc.coderm.cascade.idempotent.executor;

/**
 * 幂等执行器的业务回调接口。
 *
 * <p>与 {@link java.util.concurrent.Callable} 的区别在于允许抛出 {@link Throwable}，
 * 以便直接透传 AOP 场景中的业务异常（包括受检异常）。
 */
@FunctionalInterface
public interface IdempotentBusiness {

    Object call() throws Throwable;
}
