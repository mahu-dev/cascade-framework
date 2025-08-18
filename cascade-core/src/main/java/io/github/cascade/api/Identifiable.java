package io.github.cascade.api;

/**
 * 可标识接口
 */
public interface Identifiable {
    
    /**
     * 获取唯一标识符
     */
    String getId();
    
    /**
     * 获取显示名称
     */
    default String getDisplayName() {
        return getId();
    }
}