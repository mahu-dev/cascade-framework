package cc.coderm.cascade.idempotent.aspect;

import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;

/**
 * 基于当前 HTTP 请求解析 Header。
 */
public class ServletRequestIdempotentHeaderResolver implements IdempotentHeaderResolver {

    private static final String SERVLET_REQUEST_ATTRIBUTES_CLASS =
            "org.springframework.web.context.request.ServletRequestAttributes";
    private static final Method GET_REQUEST_METHOD = resolveGetRequestMethod();

    @Override
    public String resolve(String headerName) {
        if (!StringUtils.hasText(headerName)) {
            return null;
        }
        Object request = currentRequest();
        if (request == null) {
            return null;
        }
        Method getHeaderMethod = ReflectionUtils.findMethod(request.getClass(), "getHeader", String.class);
        if (getHeaderMethod == null) {
            return null;
        }
        Object headerValue = ReflectionUtils.invokeMethod(getHeaderMethod, request, headerName);
        return headerValue instanceof String value ? value : null;
    }

    private static Object currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || GET_REQUEST_METHOD == null) {
            return null;
        }
        if (!ClassUtils.isAssignableValue(GET_REQUEST_METHOD.getDeclaringClass(), attributes)) {
            return null;
        }
        return ReflectionUtils.invokeMethod(GET_REQUEST_METHOD, attributes);
    }

    private static Method resolveGetRequestMethod() {
        ClassLoader classLoader = ServletRequestIdempotentHeaderResolver.class.getClassLoader();
        if (!ClassUtils.isPresent(SERVLET_REQUEST_ATTRIBUTES_CLASS, classLoader)) {
            return null;
        }
        Class<?> attributesType = ClassUtils.resolveClassName(SERVLET_REQUEST_ATTRIBUTES_CLASS, classLoader);
        return ReflectionUtils.findMethod(attributesType, "getRequest");
    }
}
