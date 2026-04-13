package cc.coderm.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分布式锁测试请求DTO
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:32
 * =============================
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LockTestDTO {

    /**
     * 订单ID
     */
    private Long orderId;

    /**
     * 商品SKU ID
     */
    private Long skuId;

    /**
     * 扣减数量
     */
    private Integer quantity;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 仓库ID（用于联锁测试）
     */
    private Long warehouseId;

    /**
     * 资源ID（用于红锁测试）
     */
    private String resourceId;

    /**
     * 业务执行时长（毫秒，用于模拟耗时操作）
     */
    private Long executionTime;
}
