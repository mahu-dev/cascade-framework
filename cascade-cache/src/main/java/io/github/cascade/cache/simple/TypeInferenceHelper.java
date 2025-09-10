package io.github.cascade.cache.simple;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 类型推断辅助工具类
 *
 * @author cascade
 */
public class TypeInferenceHelper {
    private static final Logger log = LoggerFactory.getLogger(TypeInferenceHelper.class);

    private static final Map<String, Class<?>> LITERAL_PATTERNS = Map.of(
            "^'.*'$|^\".*\"$", String.class,
            "^\\d+$", Integer.class,
            "^\\d+[Ll]$", Long.class,
            "^\\d*\\.\\d+[Ff]?$", Float.class,
            "^\\d*\\.\\d+[Dd]?$", Double.class,
            "^true$|^false$", Boolean.class
    );

    public Class<?> inferType(String expression, JoinPoint joinPoint) {
        if (!StringUtils.hasText(expression)) {
            return inferDefaultKeyType(joinPoint);
        }

        // 使用策略模式处理不同类型的表达式
        for (Map.Entry<String, Class<?>> entry : LITERAL_PATTERNS.entrySet()) {
            if (expression.matches(entry.getKey())) {
                return entry.getValue();
            }
        }

        return inferComplexType(expression, joinPoint);
    }

    private Class<?> inferComplexType(String expression, JoinPoint joinPoint) {
        String expr = expression.trim();

        try {
            // 使用策略分发，避免复杂嵌套条件
            Class<?> numericType = inferNumericLiteralType(expr);
            if (numericType != null) {
                return numericType;
            }

            Class<?> parameterType = inferParameterReferenceType(expr, joinPoint);
            if (parameterType != null) {
                return parameterType;
            }

            Class<?> expressionType = inferSpecialExpressionType(expr);
            if (expressionType != null) {
                return expressionType;
            }

            return Object.class;

        } catch (RuntimeException e) {
            log.debug("SpEL表达式类型推断失败: {}, 使用Object类型, 错误: {}", expr, e.getMessage());
            return Object.class;
        }
    }

    /**
     * 推断数字字面量类型
     */
    private static Class<?> inferNumericLiteralType(String expr) {
        if (!expr.matches("\\d+[LlFfDd]?")) {
            return null;
        }

        if (expr.endsWith("L") || expr.endsWith("l")) {
            return Long.class;
        }

        if (expr.endsWith("F") || expr.endsWith("f")) {
            return Float.class;
        }

        if (expr.endsWith("D") || expr.endsWith("d")) {
            return Double.class;
        }

        if (expr.contains(".")) {
            return Double.class;
        }

        return Integer.class;
    }

    /**
     * 推断参数引用类型
     */
    private Class<?> inferParameterReferenceType(String expr, JoinPoint joinPoint) {
        // 参数引用 #p0, #p1 等
        if (expr.matches("#p\\d+")) {
            return inferParameterType(expr, joinPoint);
        }

        // 单独的参数名引用 #user, #paramName 等
        if (expr.startsWith("#") && !expr.contains(".")) {
            String paramName = expr.substring(1);
            return inferParameterTypeByName(paramName, joinPoint);
        }

        // 参数属性访问 #p0.field, #paramName.field
        if (expr.startsWith("#") && expr.contains(".")) {
            return resolvePropertyType(joinPoint, expr);
        }

        return null;
    }

    /**
     * 推断特殊表达式类型
     */
    private Class<?> inferSpecialExpressionType(String expr) {
        // 字符串拼接表达式
        if (expr.contains(" + ")) {
            return String.class;
        }

        // 条件表达式 condition ? value1 : value2
        if (expr.contains(" ? ") && expr.contains(" : ")) {
            return Object.class;
        }

        // 静态方法调用 T(Class).method()
        if (expr.startsWith("T(") && expr.contains(").")) {
            return inferStaticMethodReturnType(expr);
        }

        return null;
    }

    /**
     * 推断默认键的类型（基于方法参数）
     */
    private static Class<?> inferDefaultKeyType(JoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] paramTypes = methodSignature.getParameterTypes();

        if (paramTypes.length == 0) {
            return String.class; // 无参数时使用 "default" 字符串
        } else if (paramTypes.length == 1) {
            return paramTypes[0]; // 单参数时使用参数类型
        } else {
            return String.class; // 多参数时生成组合字符串
        }
    }

    /**
     * 根据参数索引推断类型
     */
    private static Class<?> inferParameterType(String expr, JoinPoint joinPoint) {
        int paramIndex = Integer.parseInt(expr.substring(2)); // 去掉 "#p"
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Class<?>[] paramTypes = methodSignature.getParameterTypes();

        if (paramIndex >= 0 && paramIndex < paramTypes.length) {
            return paramTypes[paramIndex];
        }
        return Object.class;
    }

    /**
     * 根据参数名推断类型
     */
    private static Class<?> inferParameterTypeByName(String paramName, JoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = methodSignature.getParameterNames();
        Class<?>[] paramTypes = methodSignature.getParameterTypes();

        for (int i = 0; i < paramNames.length; i++) {
            if (paramName.equals(paramNames[i])) {
                return paramTypes[i];
            }
        }
        return Object.class;
    }

    /**
     * 解析属性访问类型 #p0.field, #paramName.field
     */
    private Class<?> resolvePropertyType(JoinPoint joinPoint, String expr) {
        try {
            // 解析参数部分
            String paramPart;
            String propertyPath;

            int dotIndex = expr.indexOf('.');
            if (dotIndex > 0) {
                paramPart = expr.substring(1, dotIndex); // 去掉开头的#
                propertyPath = expr.substring(dotIndex + 1);
            } else {
                return Object.class;
            }

            // 获取参数类型
            Class<?> paramType = null;
            MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();

            if (paramPart.matches("p\\d+")) {
                // #p0, #p1 格式
                int paramIndex = Integer.parseInt(paramPart.substring(1));
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                if (paramIndex >= 0 && paramIndex < paramTypes.length) {
                    paramType = paramTypes[paramIndex];
                }
            } else {
                // #paramName 格式
                String[] paramNames = methodSignature.getParameterNames();
                Class<?>[] paramTypes = methodSignature.getParameterTypes();
                for (int i = 0; i < paramNames.length; i++) {
                    if (paramPart.equals(paramNames[i])) {
                        paramType = paramTypes[i];
                        break;
                    }
                }
            }

            if (paramType != null) {
                return resolveFieldType(paramType, propertyPath);
            }

        } catch (RuntimeException e) {
            log.debug("属性类型解析失败: {}, 错误: {}", expr, e.getMessage());
        }

        return Object.class;
    }

    /**
     * 解析字段类型（通过反射）
     */
    private Class<?> resolveFieldType(Class<?> rootType, String fieldPath) {
        Class<?> currentType = rootType;
        String[] fieldNames = fieldPath.split("\\.");

        for (String fieldName : fieldNames) {
            try {
                // 尝试查找getter方法
                Method getter = findGetterMethod(currentType, fieldName);
                if (getter != null) {
                    currentType = getter.getReturnType();
                } else {
                    // 尝试直接字段访问
                    Field field = currentType.getDeclaredField(fieldName);
                    currentType = field.getType();
                }
            } catch (Exception e) {
                log.debug("字段类型解析失败: {}.{}, 使用Object类型", currentType.getSimpleName(), fieldName);
                return Object.class;
            }
        }

        return currentType;
    }

    /**
     * 查找getter方法
     */
    private static Method findGetterMethod(Class<?> clazz, String fieldName) {
        String capitalizedFieldName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

        try {
            return clazz.getMethod("get" + capitalizedFieldName);
        } catch (NoSuchMethodException e1) {
            try {
                return clazz.getMethod("is" + capitalizedFieldName);
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    /**
     * 推断静态方法调用的返回类型
     */
    private static Class<?> inferStaticMethodReturnType(String expr) {
        if (expr.contains("String.valueOf")) {
            return String.class;
        } else if (expr.contains("Integer.valueOf") || expr.contains("Integer.parseInt")) {
            return Integer.class;
        } else if (expr.contains("Long.valueOf") || expr.contains("Long.parseLong")) {
            return Long.class;
        } else {
            return Object.class;
        }
    }

    /**
     * 解析缓存值的类型（方法返回值类型）
     */
    public Class<?> inferValueType(JoinPoint joinPoint) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        return method.getReturnType();
    }
}