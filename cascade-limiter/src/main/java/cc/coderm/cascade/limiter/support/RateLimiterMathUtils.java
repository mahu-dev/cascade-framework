package cc.coderm.cascade.limiter.support;

/**
 * 限流算法相关的数学工具类
 * <p>
 * 提供限流算法中常用的数学计算方法，如向上取整除法等。
 * 这些工具方法在多个限流算法实现中被复用，避免代码重复。
 *
 * @author lionel lionelk@163.com
 * =============================
 * Date: 2026/04/11
 * Time: 18:00
 * =============================
 */
public final class RateLimiterMathUtils {

    private RateLimiterMathUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 执行向上取整的除法运算
     * <p>
     * 标准 Java 整数除法会向下取整（向零截断），此方法实现向上取整，
     * 确保能完整覆盖指定范围的计算场景。
     * <p>
     * <b>数学原理：</b>
     * <pre>
     * 向上取整公式：ceil(a / b) = (a + b - 1) / b
     *
     * 示例：
     *   ceilDiv(10, 3)  = (10 + 3 - 1) / 3 = 12 / 3 = 4  (标准除法：10 / 3 = 3)
     *   ceilDiv(11, 3)  = (11 + 3 - 1) / 3 = 13 / 3 = 4  (标准除法：11 / 3 = 3)
     *   ceilDiv(12, 3)  = (12 + 3 - 1) / 3 = 14 / 3 = 4  (标准除法：12 / 3 = 4)
     * </pre>
     * <p>
     * <b>应用场景：</b>
     * <ul>
     *   <li><b>令牌桶算法</b>：计算等待时间时向上取整，确保等待时间足以补充所需令牌</li>
     *   <li><b>滑动窗口算法</b>：计算槽位数量时向上取整，确保所有时间点被槽位覆盖</li>
     * </ul>
     * <p>
     * <b>注意事项：</b>
     * <ul>
     *   <li>此方法假设 {@code denominator > 0}，调用方需确保除数大于 0</li>
     *   <li>如果 {@code numerator < 0}，结果可能不符合数学上的"向上取整"定义</li>
     *   <li>适用于非负整数场景，与限流算法的语义完全匹配</li>
     * </ul>
     *
     * @param numerator   被除数，必须 ≥ 0
     * @param denominator 除数，必须 > 0
     * @return 向上取整后的除法结果
     * @throws ArithmeticException 如果 denominator ≤ 0
     */
    public static long ceilDiv(long numerator, long denominator) {
        if (denominator <= 0) {
            throw new ArithmeticException("Denominator must be positive, but was: " + denominator);
        }
        return (numerator + denominator - 1) / denominator;
    }
}