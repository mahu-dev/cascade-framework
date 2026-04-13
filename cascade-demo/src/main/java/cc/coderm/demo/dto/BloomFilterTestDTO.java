package cc.coderm.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 布隆过滤器测试请求DTO
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:33
 * =============================
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BloomFilterTestDTO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 订单ID
     */
    private String orderId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 是否存在（用于初始化测试数据）
     */
    private Boolean exists;
}
