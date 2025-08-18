package io.github.cascade.cache.util;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 高级类型推断工具类
 * 通过调用栈分析和方法签名推断泛型类型
 * 
 * @author cascade
 */
public class TypeInference {
    
    /**
     * 从调用上下文推断Cache的泛型类型
     * 
     * @return 推断出的类型数组 [keyType, valueType]，失败时返回null
     */
    public static Class<?>[] inferCacheTypes() {
        try {
            // 获取调用栈
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            
            // 查找调用方法中的类型信息
            for (StackTraceElement element : stackTrace) {
                try {
                    Class<?> callerClass = Class.forName(element.getClassName());
                    String methodName = element.getMethodName();
                    
                    // 查找匹配的方法
                    for (Method method : callerClass.getDeclaredMethods()) {
                        if (method.getName().equals(methodName)) {
                            Class<?>[] types = analyzeMethodForCacheTypes(method);
                            if (types != null) {
                                return types;
                            }
                        }
                    }
                } catch (ClassNotFoundException | SecurityException e) {
                    // 忽略无法访问的类
                }
            }
        } catch (Exception e) {
            // 推断失败，返回null
        }
        
        return null;
    }
    
    /**
     * 分析方法签名中的Cache类型信息
     */
    private static Class<?>[] analyzeMethodForCacheTypes(Method method) {
        // 分析返回类型
        Type returnType = method.getGenericReturnType();
        Class<?>[] types = extractCacheTypes(returnType);
        if (types != null) {
            return types;
        }
        
        // 分析参数类型
        Type[] parameterTypes = method.getGenericParameterTypes();
        for (Type paramType : parameterTypes) {
            types = extractCacheTypes(paramType);
            if (types != null) {
                return types;
            }
        }
        
        // 分析局部变量类型（编译时信息，运行时通常不可用）
        
        return null;
    }
    
    /**
     * 从Type中提取Cache<K,V>的K、V类型
     */
    private static Class<?>[] extractCacheTypes(Type type) {
        if (type instanceof ParameterizedType paramType) {
            Type rawType = paramType.getRawType();
            
            // 检查是否是Cache类型
            if (rawType instanceof Class<?> rawClass && 
                io.github.cascade.cache.api.Cache.class.isAssignableFrom(rawClass)) {
                
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length == 2) {
                    Class<?> keyType = null;
                    Class<?> valueType = null;
                    
                    if (typeArgs[0] instanceof Class) {
                        keyType = (Class<?>) typeArgs[0];
                    }
                    if (typeArgs[1] instanceof Class) {
                        valueType = (Class<?>) typeArgs[1];
                    }
                    
                    if (keyType != null && valueType != null) {
                        return new Class<?>[]{keyType, valueType};
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 简化的类型推断 - 基于常见模式
     */
    public static Class<?>[] inferFromVariableName(String variableName) {
        if (variableName == null || variableName.trim().isEmpty()) {
            return null;
        }
        
        String name = variableName.toLowerCase();
        
        // 基于变量命名模式推断
        if (name.contains("user")) {
            return new Class<?>[]{String.class, findClassByName("User")};
        } else if (name.contains("product")) {
            return new Class<?>[]{Long.class, findClassByName("Product")};
        } else if (name.contains("order")) {
            return new Class<?>[]{String.class, findClassByName("Order")};
        } else if (name.contains("string")) {
            return new Class<?>[]{String.class, String.class};
        }
        
        return null;
    }
    
    /**
     * 根据类名查找Class对象
     */
    private static Class<?> findClassByName(String className) {
        try {
            // 尝试常见包路径
            String[] commonPackages = {
                "io.github.cascade.cache.example.",
                "cc.coderm.demo.model.",
                "com.example.model.",
                "java.lang."
            };
            
            for (String pkg : commonPackages) {
                try {
                    return Class.forName(pkg + className);
                } catch (ClassNotFoundException ignored) {
                    // 继续尝试下一个包
                }
            }
            
            // 尝试不带包名
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return Object.class; // 默认返回Object
        }
    }
}