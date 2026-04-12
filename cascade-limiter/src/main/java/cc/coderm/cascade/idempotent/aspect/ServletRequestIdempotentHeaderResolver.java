package cc.coderm.cascade.idempotent.aspect;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 基于当前 HTTP 请求解析 Header。
 */
public class ServletRequestIdempotentHeaderResolver implements IdempotentHeaderResolver {

    private final ObjectProvider<HttpServletRequest> requestProvider;

    public ServletRequestIdempotentHeaderResolver(ObjectProvider<HttpServletRequest> requestProvider) {
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    }

    @Override
    public String resolve(String headerName) {
        if (!StringUtils.hasText(headerName)) {
            return null;
        }
        HttpServletRequest request = requestProvider.getIfAvailable();
        if (request == null) {
            return null;
        }
        return request.getHeader(headerName);
    }
}

