package io.github.cascade.lock.key;

import io.github.cascade.lock.exception.LockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 基于 SpEL 的 Key 生成器
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:04
 * =============================
 */
public class SpelKeyGenerator implements KeyGenerator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
    private final BeanResolver beanResolver;

    public SpelKeyGenerator() {
        this.beanResolver = null;
    }

    public SpelKeyGenerator(BeanFactory beanFactory) {
        this.beanResolver = new BeanFactoryResolver(beanFactory);
    }

    @Override
    public String generate(String keyExpression, ProceedingJoinPoint joinPoint, Method method) {
        if (!StringUtils.hasText(keyExpression)) {
            return joinPoint.getTarget().getClass().getSimpleName() + "." + method.getName();
        }

        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), discoverer);
        if (beanResolver != null) {
            context.setBeanResolver(beanResolver);
        }

        Object value = parser.parseExpression(keyExpression).getValue(context);
        if (value == null) {
            throw new LockException(
                    "SpEL 表达式计算结果为空，无法生成锁 key: " + keyExpression,
                    method.toGenericString()
            );
        }
        String resolved = value.toString();
        if (!StringUtils.hasText(resolved)) {
            throw new LockException(
                    "SpEL 表达式计算结果为空白，无法生成锁 key: " + keyExpression,
                    method.toGenericString()
            );
        }
        return resolved.trim();
    }
}
