package cc.coderm.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 幂等测试请求DTO
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/4/13
 * Time: 14:31
 * =============================
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentTestDTO {

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 商品数量
     */
    private Integer quantity;

    /**
     * 金额
     */
    private BigDecimal amount;

    /**
     * 支付ID
     */
    private String paymentId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 是否模拟失败（用于测试 deleteOnFailure）
     */
    private Boolean simulateFailure;

    /**
     * 手机号（用于发送短信测试）
     */
    private String phone;

    /**
     * 短信模板ID
     */
    private String templateId;
}
