package io.github.cascade;

import io.github.cascade.api.CascadeComponent;
import io.github.cascade.exception.ComponentException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cascade组件注册中心
 */
public class CascadeRegistry {
    
    private final Map<Class<? extends CascadeComponent>, CascadeComponent> components = new ConcurrentHashMap<>();
    private final Map<String, CascadeComponent> componentsByName = new ConcurrentHashMap<>();
    
    /**
     * 注册组件
     */
    public <T extends CascadeComponent> void registerComponent(T component) {
        @SuppressWarnings("unchecked")
        Class<T> componentType = (Class<T>) component.getClass();
        
        components.put(componentType, component);
        componentsByName.put(component.getName(), component);
    }
    
    /**
     * 获取组件
     */
    @SuppressWarnings("unchecked")
    public <T extends CascadeComponent> Optional<T> getComponent(Class<T> componentType) {
        return Optional.ofNullable((T) components.get(componentType));
    }
    
    /**
     * 获取组件（通过名称）
     */
    @SuppressWarnings("unchecked")
    public <T extends CascadeComponent> Optional<T> getComponent(String name) {
        return Optional.ofNullable((T) componentsByName.get(name));
    }
    
    /**
     * 获取组件（如果不存在则抛出异常）
     */
    public <T extends CascadeComponent> T getRequiredComponent(Class<T> componentType) {
        return getComponent(componentType)
                .orElseThrow(() -> new ComponentException(
                    String.format("Component %s not found. Please check dependencies.", 
                        componentType.getSimpleName())
                ));
    }
    
    /**
     * 获取组件（通过名称，如果不存在则抛出异常）
     */
    public <T extends CascadeComponent> T getRequiredComponent(String name) {
        return this.<T>getComponent(name)
                .orElseThrow(() -> new ComponentException(
                    String.format("Component %s not found. Please check dependencies.", name)
                ));
    }
    
    /**
     * 检查组件是否存在
     */
    public boolean hasComponent(Class<? extends CascadeComponent> componentType) {
        return components.containsKey(componentType);
    }
    
    /**
     * 检查组件是否存在（通过名称）
     */
    public boolean hasComponent(String name) {
        return componentsByName.containsKey(name);
    }
    
    /**
     * 移除组件
     */
    public <T extends CascadeComponent> Optional<T> removeComponent(Class<T> componentType) {
        @SuppressWarnings("unchecked")
        T component = (T) components.remove(componentType);
        if (component != null) {
            componentsByName.remove(component.getName());
        }
        return Optional.ofNullable(component);
    }
    
    /**
     * 移除组件（通过名称）
     */
    public <T extends CascadeComponent> Optional<T> removeComponent(String name) {
        @SuppressWarnings("unchecked")
        T component = (T) componentsByName.remove(name);
        if (component != null) {
            components.remove(component.getClass());
        }
        return Optional.ofNullable(component);
    }
    
    /**
     * 获取所有组件
     */
    public Collection<CascadeComponent> getAllComponents() {
        return new ArrayList<>(components.values());
    }
    
    /**
     * 获取所有组件名称
     */
    public Set<String> getAllComponentNames() {
        return new HashSet<>(componentsByName.keySet());
    }
    
    /**
     * 获取组件数量
     */
    public int getComponentCount() {
        return components.size();
    }
    
    /**
     * 清空所有组件
     */
    public void clear() {
        components.clear();
        componentsByName.clear();
    }
}