package it.cnr.ilc.lexo;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LexOFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesValidRequestIdAndRestoresPreviousContext() throws Exception {
        Map<String, String> responseHeaders = new HashMap<String, String>();
        HttpServletRequest request = request("request-123");
        HttpServletResponse response = response(responseHeaders, 204);
        MDC.put("container", "preserved");
        FilterChain chain = (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo("request-123");
            assertThat(MDC.get("method")).isEqualTo("GET");
            assertThat(MDC.get("path")).isEqualTo("/service/texts");
            assertThat(MDC.get("container")).isNull();
        };

        new LexOFilter().doFilter(request, response, chain);

        assertThat(responseHeaders.get("X-Request-ID"))
                .isEqualTo("request-123");
        assertThat(MDC.get("container")).isEqualTo("preserved");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        Map<String, String> responseHeaders = new HashMap<String, String>();
        new LexOFilter().doFilter(request("bad\nrequest"),
                response(responseHeaders, 200), (req, res) -> { });

        assertThat(responseHeaders.get("X-Request-ID"))
                .matches("[0-9a-f-]{36}");
    }

    private static HttpServletRequest request(String requestId) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                LexOFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName())) {
                        return requestId;
                    }
                    if ("getMethod".equals(method.getName())) {
                        return "GET";
                    }
                    if ("getRequestURI".equals(method.getName())) {
                        return "/service/texts";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static HttpServletResponse response(
            Map<String, String> headers, int status) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                LexOFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("setHeader".equals(method.getName())) {
                        headers.put((String) args[0], (String) args[1]);
                        return null;
                    }
                    if ("getStatus".equals(method.getName())) {
                        return status;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
