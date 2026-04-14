package cc.coderm.cascade.limiter.aspect;

import cc.coderm.cascade.limiter.annotation.RateLimit;
import cc.coderm.cascade.limiter.config.CascadeLimiterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 限流 Redis Key 解析组件
 * <p>
 * 负责解析和生成限流器的 Redis key，支持静态 key 和动态 SpEL 表达式两种模式。
 * 从 {@link RateLimitAspect} 中提取为独立组件，提高代码的可维护性和可测试性。
 * <p>
 * <b>职责范围：</b>
 * <ul>
 *   <li><b>Key 生成</b>：根据注解配置生成限流器的唯一标识 key</li>
 *   <li><b>SpEL 解析</b>：支持使用 SpEL 表达式动态生成 key</li>
 *   <li><b>安全加固</b>：提供安全模式，禁用 SpEL 的危险功能</li>
 *   <li><b>验证规范</b>：验证生成的 key 符合长度和字符白名单要求</li>
 *   <li><b>性能优化</b>：缓存已解析的 SpEL 表达式，避免重复解析</li>
 * </ul>
 * <p>
 * <b>为什么设计为实例组件：</b>
 * <pre>
 * // 需要持有以下依赖组件的引用：
 * 1. CascadeLimiterProperties - 读取 key 前缀、安全模式等配置
 * 2. BeanFactory           - SpEL 表达式解析时访问 Bean（不安全模式）
 *
 * // 需要维护以下状态：
 * 3. ExpressionParser      - SpEL 表达式解析器
 * 4. ParameterNameDiscoverer - 方法参数名发现器
 * 5. 表达式缓存            - ConcurrentHashMap，提高性能
 * 6. 警告日志标记          - AtomicBoolean，避免日志洪水
 * </pre>
 * <p>
 * <b>Key 生成模式：</b>
 * <table border="1">
 *   <tr><th>模式</th><th>示例</th><th>结果</th></tr>
 *   <tr><td>静态默认</td><td>@RateLimit(key="")</td><td>cascade:limiter:ClassName:methodName</td></tr>
 *   <tr><td>静态自定义</td><td>@RateLimit(key="api:user")</td><td>cascade:limiter:api:user</td></tr>
 *   <tr><td>动态参数</td><td>@RateLimit(key="#userId")</td><td>cascade:limiter:123</td></tr>
 *   <tr><td>动态组合</td><td>@RateLimit(key="'api:' + #type")</td><td>cascade:limiter:api:VIP</td></tr>
 * </table>
 * <p>
 * <b>安全模式说明：</b>
 * <ul>
 *   <li><b>安全模式（默认推荐）</b>：禁用 Bean 访问、方法调用、类加载等危险功能，
 *       限制 SpEL 只能访问方法参数和目标对象属性</li>
 *   <li><b>不安全模式</b>：允许访问 Spring Bean 和调用任意方法，功能强大但有安全风险，
 *       会记录一次性警告日志提醒开发者</li>
 * </ul>
 * <p>
 * <b>性能优化：</b>
 * <ul>
 *   <li>SpEL 表达式解析结果缓存在 {@code ConcurrentHashMap} 中</li>
 *   <li>避免重复解析相同的表达式字符串</li>
 *   <li>使用 {@code computeIfAbsent} 保证线程安全</li>
 * </ul>
 *
 * @see RateLimit
 * @see CascadeLimiterProperties
 */
@Slf4j
final class RateLimitKeyResolver {

    private static final int MAX_EVALUATED_KEY_LENGTH = 256;
    private static final Pattern KEY_WHITELIST = Pattern.compile("[a-zA-Z0-9:_./\\-]+");
    private static final TypeLocator DENY_ALL_TYPE_LOCATOR = typeName ->
    {
        throw new EvaluationException("Type references are disabled in rate-limit key SpEL: " + typeName);
    };

    private final CascadeLimiterProperties properties;
    private final BeanFactory beanFactory;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Expression> keyExpressionCache = new ConcurrentHashMap<>();
    private final AtomicBoolean unsafeModeWarningLogged = new AtomicBoolean(false);

    RateLimitKeyResolver(CascadeLimiterProperties properties, BeanFactory beanFactory) {
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    /**
     * 解析并生成限流 Redis key
     * <p>
     * 根据 {@code @RateLimit} 注解配置生成限流器的 Redis key，支持两种模式：
     * <ul>
     *   <li><b>静态 key</b>：当 {@code key()} 为空字符串时，使用"包名:类名:方法名"格式</li>
     *   <li><b>动态 key</b>：使用 SpEL 表达式，支持方法参数、目标对象属性等动态内容</li>
     * </ul>
     * <p>
     * <b>处理流程：</b>
     * <ol>
     *   <li>获取配置的前缀（如 {@code cascade:limiter:}）</li>
     *   <li>如果 key 表达式为空，生成默认 key（包名:类名:方法名）</li>
     *   <li>如果 key 表达式非空，使用 SpEL 解析：
     *     <ul>
     *       <li>创建 SpEL 求值上下文（目标对象作为 root）</li>
     *       <li>根据安全模式配置上下文（安全/不安全）</li>
     *       <li>解析并计算表达式</li>
     *       <li>验证和规范化计算结果</li>
     *     </ul>
     *   </li>
     *   <li>返回前缀 + 计算结果的完整 key</li>
     * </ol>
     * <p>
     * <b>SpEL 表达式示例：</b>
     * <pre>
     * // 使用方法参数
     * @RateLimit(key = "#userId")  → key 为 userId 参数的值
     *
     * // 使用目标对象属性
     * @RateLimit(key = "#user.id")  → key 为 user 对象的 id 属性值
     *
     * // 组合使用
     * @RateLimit(key = "'api:' + #userType + ':' + #userId")  → key 为 "api:VIP:123"
     * </pre>
     * <p>
     * <b>安全模式说明：</b>
     * <ul>
     *   <li><b>安全模式（默认推荐）</b>：禁用 Bean 访问、方法调用、类加载等危险功能，
     *       限制 SpEL 只能访问方法参数和目标对象属性</li>
     *   <li><b>不安全模式</b>：允许访问 Spring Bean 和调用任意方法，功能强大但有安全风险，
     *       会记录一次性警告日志提醒开发者</li>
     * </ul>
     *
     * @param rateLimit {@code @RateLimit} 注解实例，包含 key 表达式等配置
     * @param method     被拦截的目标方法
     * @param args       方法参数数组，用于 SpEL 表达式引用（如 {@code #paramName}）
     * @param target     目标对象，用于 SpEL 表达式访问对象属性
     * @return 完整的 Redis key，格式为"前缀 + 计算结果"
     */
    String resolveKey(RateLimit rateLimit, Method method, Object[] args, Object target) {
        // 获取配置的 Redis key 前缀
        String prefix = properties.getRedisKeyPrefix();
        // 获取注解中配置的 key 表达式
        String keyExpr = rateLimit.key();

        if (keyExpr.isEmpty()) {
            // 静态默认 key：类名:方法名
            return prefix + method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        }

        // SpEL 解析，使用 target 作为 rootObject，支持 SpEL 访问目标对象属性
        MethodBasedEvaluationContext ctx = new MethodBasedEvaluationContext(target, method, args, nameDiscoverer);
        if (properties.isKeyExpressionSafeMode()) {
            // 安全模式：禁用危险功能
            hardenKeyExpressionContext(ctx);
        } else {
            // 不安全模式：允许访问 Bean 和调用方法，记录警告
            warnUnsafeKeyExpressionModeIfNeeded();
            ctx.setBeanResolver(new BeanFactoryResolver(beanFactory));
        }

        // 从缓存获取或解析表达式（性能优化）
        Expression expression = keyExpressionCache.computeIfAbsent(keyExpr, parser::parseExpression);
        // 计算 SpEL 表达式的值
        String evaluated = expression.getValue(ctx, String.class);
        // 规范化并验证，然后添加前缀
        return prefix + normalizeAndValidateEvaluatedKey(evaluated);
    }

    /**
     * 加固 SpEL 表达式上下文的安全配置（安全模式）
     * <p>
     * 当 {@code key-expression-safe-mode=true} 时，调用此方法禁用 SpEL 的所有危险功能：
     * <ul>
     *   <li>禁用 Bean 解析器：通过 {@code setBeanResolver(null)} 禁止访问 Spring Bean</li>
     *   <li>清空方法解析器：禁止调用任意方法</li>
     *   <li>清空构造器解析器：禁止调用任意构造器</li>
     *   <li>设置拒绝所有类型访问的类型定位器：禁止类加载和类型引用</li>
     * </ul>
     * <p>
     * <b>安全原理：</b>
     * SpEL 表达式默认具有强大的功能，包括：
     * <ul>
     *   <li>访问 Spring Bean（可能调用任意业务方法）</li>
     *   <li>方法调用和构造器调用（可能执行任意代码）</li>
     *   <li>类加载和类型引用（可能导致类加载攻击）</li>
     * </ul>
     * 通过禁用这些功能，将 SpEL 表达式限制在纯粹的字符串操作范围内，
     * 防止恶意用户通过限流 key 表达式执行任意代码。
     * <p>
     * <b>关于 null 参数的说明：</b>
     * {@code setBeanResolver(null)} 是有意为之的设计，用于完全禁用 Bean 解析功能。
     * 这是 Spring 框架支持的安全加固模式，不会导致 NullPointerException。
     *
     * @param ctx SpEL 表达式求值上下文
     */
    private static void hardenKeyExpressionContext(MethodBasedEvaluationContext ctx) {
        // 禁用 Bean 解析器：防止 SpEL 访问 Spring Bean
        ctx.setBeanResolver(null);
        // 禁用方法和构造器解析：防止 SpEL 调用任意方法
        ctx.setMethodResolvers(List.of());
        ctx.setConstructorResolvers(List.of());
        // 设置拒绝所有类型访问的类型定位器：防止类加载攻击
        ctx.setTypeLocator(DENY_ALL_TYPE_LOCATOR);
    }

    /**
     * 在必要时记录不安全模式的警告（仅记录一次）
     * <p>
     * 当 {@code key-expression-safe-mode=false} 时，此方法会在首次限流调用时记录警告日志，
     * 提醒开发者 SpEL 表达式具有访问 Spring Bean 和调用任意方法的强大功能，
     * 可能带来安全风险。
     * <p>
     * <b>一次性警告机制：</b>
     * 使用 {@code AtomicBoolean.compareAndSet(false, true)} 确保警告只记录一次，
     * 避免在高并发场景下产生日志洪水（日志重复记录）。
     * <p>
     * <b>安全建议：</b>
     * 如果不需要在 SpEL 表达式中引用 Spring Bean 或调用方法，
     * 建议启用 {@code key-expression-safe-mode=true} 以获得更高的安全性。
     * <p>
     * <b>何时触发：</b>
     * 当 {@code properties.isKeyExpressionSafeMode()} 返回 false 时，
     * 在首次调用 {@link #resolveKey} 方法时触发此警告。
     */
    private void warnUnsafeKeyExpressionModeIfNeeded() {
        // 原子操作：仅在首次调用时记录警告，避免日志洪水
        if (unsafeModeWarningLogged.compareAndSet(false, true)) {
            log.warn("[CascadeLimiter] key-expression-safe-mode is disabled. " +
                    "RateLimit key SpEL can access @bean/T()/method invocation and may execute unsafe logic.");
        }
    }

    /**
     * 规范化并验证 SpEL 表达式计算出的 key
     * <p>
     * 对 SpEL 表达式计算结果进行安全验证，确保生成的 Redis key 符合要求。
     * 这是防止 SpEL 注入攻击和 Redis key 污染的重要安全措施。
     * <p>
     * <b>验证规则：</b>
     * <ol>
     *   <li><b>非空检查</b>：计算结果不能为空或空白</li>
     *   <li><b>长度限制</b>：规范化后的 key 长度 ≤ {@value MAX_EVALUATED_KEY_LENGTH}</li>
     *   <li><b>字符白名单</b>：只允许字母、数字、冒号、下划线、点、斜杠、反斜杠、连字符</li>
     * </ol>
     * <p>
     * <b>安全考虑：</b>
     * <ul>
     *   <li>防止 SpEL 表达式生成恶意或超长的 Redis key</li>
     *   <li>避免特殊字符导致的 Redis 命令注入或数据污染</li>
     *   <li>限制 key 长度防止 Redis 内存耗尽</li>
     * </ul>
     * <p>
     * <b>规范化操作：</b>去除首尾空格（{@code trim()}），确保 key 的紧凑性。
     *
     * @param evaluated SpEL 表达式计算出的字符串
     * @return 规范化后的 key，通过所有安全验证
     * @throws IllegalArgumentException 如果 key 为空白、过长或包含非法字符
     */
    private static String normalizeAndValidateEvaluatedKey(String evaluated) {
        // 非空检查：计算结果不能为空白
        if (!StringUtils.hasText(evaluated)) {
            throw new IllegalArgumentException("RateLimit key expression evaluated to blank");
        }
        // 规范化：去除首尾空格
        String normalized = evaluated.trim();

        // 验证 key 长度：防止超长 key 占用过多 Redis 内存
        if (normalized.length() > MAX_EVALUATED_KEY_LENGTH) {
            throw new IllegalArgumentException("RateLimit key expression evaluated key is too long: " + normalized.length());
        }

        // 验证字符白名单：防止特殊字符导致的 Redis 命令注入或数据污染
        if (!KEY_WHITELIST.matcher(normalized).matches()) {
            throw new IllegalArgumentException("RateLimit key expression evaluated to unsupported characters: " + normalized);
        }
        return normalized;
    }
}
