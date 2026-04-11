package io.github.cascade.lock.key;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
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

    @Override
    public String generate(String keyExpression, ProceedingJoinPoint joinPoint, Method method) {
        if (!StringUtils.hasText(keyExpression)) {
            // 无表达式时使用 类名.方法名 作为 key
            return joinPoint.getTarget().getClass().getSimpleName() + "." + method.getName();
        }

        // 判断是否包含 SpEL 语法
        if (!keyExpression.contains("#") && !keyExpression.contains("'")) {
            return keyExpression;
        }

        EvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), discoverer);

        Object value = parser.parseExpression(keyExpression).getValue(context);
        return value == null ? keyExpression : value.toString();
    }
}
