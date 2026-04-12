package cc.coderm.cascade.idempotent.aspect;

/**
 * 解析幂等 key 所需的 Header 值。
 */
@FunctionalInterface
public interface IdempotentHeaderResolver {

    /**
     * 按 Header 名称解析值。
     *
     * @param headerName Header 名称
     * @return Header 值（不存在时返回 null）
     */
    String resolve(String headerName);

    static IdempotentHeaderResolver noop() {
        return headerName -> null;
    }
}

