package io.github.cascade.lock.key;

import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

/**
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/12
 * Time: 01:04
 * =============================
 */
public interface KeyGenerator {
    String generate(String keyExpression, ProceedingJoinPoint joinPoint, Method method);
}
