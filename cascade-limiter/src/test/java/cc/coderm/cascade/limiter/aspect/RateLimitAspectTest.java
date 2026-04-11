package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.algorithm.RateLimiter;
import cc.coderm.cascade.limiter.annotation.RateLimit;
import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import cc.coderm.cascade.limiter.exception.RateLimitException;
import cc.coderm.cascade.limiter.factory.RateLimiterFactory;
import cc.coderm.cascade.limiter.model.AlgorithmType;
import cc.coderm.cascade.limiter.model.RateLimitResult;
import cc.coderm.cascade.limiter.strategy.RateLimitStrategy;
import cc.coderm.cascade.limiter.support.LimiterBackendFailureTracker;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitAspectTest {

    @Test
    void shouldUseClassLevelAnnotationWhenMethodIsNotAnnotated() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false);
        ClassAnnotatedService proxy = createProxy(new ClassAnnotatedService(), aspect);

        String result = proxy.handle("u1");

        assertThat(result).isEqualTo("ok");
        assertThat(factory.lastKey).isEqualTo("rl:class:u1");
    }

    @Test
    void shouldUseClassLevelAnnotationWithJdkProxy() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false);
        ClassAnnotatedInterface proxy = createJdkProxy(new InterfaceClassAnnotatedService(), aspect);

        String result = proxy.handle("u1");

        assertThat(result).isEqualTo("ok");
        assertThat(factory.lastKey).isEqualTo("rl:class-jdk:u1");
    }

    @Test
    void shouldPreferMethodAnnotationOverClassAnnotation() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false);
        MixedAnnotatedService proxy = createProxy(new MixedAnnotatedService(), aspect);

        String result = proxy.handle();

        assertThat(result).isEqualTo("ok");
        assertThat(factory.lastKey).isEqualTo("rl:method");
    }

    @Test
    void shouldResolveBeanInSpelExpression() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false, false);
        BeanSpelService proxy = createProxy(new BeanSpelService(), aspect);

        String result = proxy.handle("u2");

        assertThat(result).isEqualTo("ok");
        assertThat(factory.lastKey).isEqualTo("rl:bean:u2");
    }

    @Test
    void shouldRejectBeanAccessInSafeSpelModeByDefault() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false);
        BeanSpelService proxy = createProxy(new BeanSpelService(), aspect);

        assertThatThrownBy(() -> proxy.handle("u2"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("keyProvider");
    }

    @Test
    void shouldRejectTypeReferenceInSafeSpelMode() {
        RecordingFactory factory = new RecordingFactory();
        RateLimitAspect aspect = createAspect(factory, false);
        TypeReferenceSpelService proxy = createProxy(new TypeReferenceSpelService(), aspect);

        assertThatThrownBy(proxy::handle)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Type references are disabled");
    }

    @Test
    void shouldFailOpenWhenLimiterThrowsAndFailOnErrorIsFalse() {
        RecordingFactory factory = new RecordingFactory();
        factory.tryAcquireError = new RedisException("redis down");
        RateLimitAspect aspect = createAspect(factory, false);
        MethodAnnotatedService proxy = createProxy(new MethodAnnotatedService(), aspect);

        String result = proxy.handle();

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void shouldFailClosedWhenLimiterThrowsAndFailOnErrorIsTrue() {
        RecordingFactory factory = new RecordingFactory();
        factory.tryAcquireError = new RedisException("redis down");
        RateLimitAspect aspect = createAspect(factory, true);
        MethodAnnotatedService proxy = createProxy(new MethodAnnotatedService(), aspect);

        assertThatThrownBy(proxy::handle).isInstanceOf(RateLimitException.class);
    }

    @Test
    void shouldNotSwallowLocalProgrammingErrorsWhenFailOpen() {
        RecordingFactory factory = new RecordingFactory();
        factory.tryAcquireError = new IllegalArgumentException("bad strategy");
        RateLimitAspect aspect = createAspect(factory, false);
        MethodAnnotatedService proxy = createProxy(new MethodAnnotatedService(), aspect);

        assertThatThrownBy(proxy::handle).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldInvokeFallbackMethodDeclaredInSuperclass() {
        RecordingFactory factory = new RecordingFactory();
        factory.forcedResult = RateLimitResult.rejected("rl:inherit", AlgorithmType.TOKEN_BUCKET, 10);
        RateLimitAspect aspect = createAspect(factory, false);
        ChildWithInheritedFallback proxy = createProxy(new ChildWithInheritedFallback(), aspect);

        String result = proxy.handle("u9");

        assertThat(result).isEqualTo("base:u9");
    }

    private static RateLimitAspect createAspect(RecordingFactory factory, boolean failOnError) {
        return createAspect(factory, failOnError, true);
    }

    private static RateLimitAspect createAspect(RecordingFactory factory, boolean failOnError, boolean safeMode) {
        CascadeLimiterProperties properties = new CascadeLimiterProperties();
        properties.setRedisKeyPrefix("rl:");
        properties.setFailOnError(failOnError);
        properties.setKeyExpressionSafeMode(safeMode);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("keyProvider", new KeyProvider());

        return new RateLimitAspect(
                factory,
                properties,
                null,  // globalFallback - 不使用全局降级处理器
                null,  // metrics - 不启用指标收集
                beanFactory,
                new LimiterBackendFailureTracker(properties)
        );
    }

    private static <T> T createProxy(T target, RateLimitAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private static <T> T createJdkProxy(T target, RateLimitAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @RateLimit(key = "'class:' + #p0")
    static class ClassAnnotatedService {
        public String handle(String userId) {
            return "ok";
        }
    }

    interface ClassAnnotatedInterface {
        String handle(String userId);
    }

    @RateLimit(key = "'class-jdk:' + #p0")
    static class InterfaceClassAnnotatedService implements ClassAnnotatedInterface {
        @Override
        public String handle(String userId) {
            return "ok";
        }
    }

    @RateLimit(key = "'class'")
    static class MixedAnnotatedService {
        @RateLimit(key = "'method'")
        public String handle() {
            return "ok";
        }
    }

    static class BeanSpelService {
        @RateLimit(key = "@keyProvider.value(#p0)")
        public String handle(String userId) {
            return "ok";
        }
    }

    static class TypeReferenceSpelService {
        @RateLimit(key = "T(java.lang.System).currentTimeMillis()")
        public String handle() {
            return "ok";
        }
    }

    static class MethodAnnotatedService {
        @RateLimit(key = "'m'")
        public String handle() {
            return "ok";
        }
    }

    static class BaseFallbackService {
        protected String baseFallback(String userId) {
            return "base:" + userId;
        }
    }

    static class ChildWithInheritedFallback extends BaseFallbackService {
        @RateLimit(key = "'inherit'", fallbackMethod = "baseFallback")
        public String handle(String userId) {
            return "ok";
        }
    }

    static class KeyProvider {
        public String value(String userId) {
            return "bean:" + userId;
        }
    }

    private static final class RecordingFactory extends RateLimiterFactory {
        private String lastKey;
        private RuntimeException tryAcquireError;
        private RateLimitResult forcedResult;

        private RecordingFactory() {
            super(List.of(new NoopRateLimiter()));
        }

        @Override
        public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy) {
            lastKey = key;
            if (tryAcquireError != null) {
                throw tryAcquireError;
            }
            if (forcedResult != null) {
                return forcedResult;
            }
            return RateLimitResult.allowed(key, strategy.getAlgorithmType(), 0);
        }
    }

    private static final class NoopRateLimiter implements RateLimiter {
        @Override
        public RateLimitResult tryAcquire(String key, RateLimitStrategy strategy, int permits) {
            return RateLimitResult.allowed(key, strategy.getAlgorithmType(), 0);
        }

        @Override
        public boolean acquire(String key, RateLimitStrategy strategy, long timeoutMs) {
            return true;
        }

        @Override
        public AlgorithmType getAlgorithmType() {
            return AlgorithmType.TOKEN_BUCKET;
        }
    }
}
