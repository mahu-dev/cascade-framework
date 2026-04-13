package cc.coderm.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存测试请求DTO
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:30
 * =============================
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheTestDTO {

    /**
     * 缓存键
     */
    private String key;

    /**
     * 缓存值
     */
    private String value;

    /**
     * 用户ID（用于条件缓存测试）
     */
    private Long userId;

    /**
     * 用户名（用于条件缓存测试）
     */
    private String username;

    /**
     * 是否启用缓存
     */
    private Boolean enabled;
}
