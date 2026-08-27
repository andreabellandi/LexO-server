package it.cnr.ilc.lexo;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author andreabellandi
 */
@WebFilter(urlPatterns = {"/faces/*", "/service/*", "/servlet/*"})
public class LexOFilter implements Filter {

    static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Logger LOGGER = LoggerFactory.getLogger(LexOFilter.class);
    public static String CONTEXT;
    public static String VERSION;

    public static String fileSystemPath;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Application lifecycle and GraphDB bootstrap are owned by
        // LexOApplicationLifecycle. This filter is request-scoped only.
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest)
                || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String requestId = requestId(httpRequest.getHeader(REQUEST_ID_HEADER));
        long startNanos = System.nanoTime();
        Throwable failure = null;
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("requestId", requestId);
        MDC.put("method", safe(httpRequest.getMethod()));
        MDC.put("path", safe(httpRequest.getRequestURI()));
        if (VERSION != null && !VERSION.trim().isEmpty()) {
            MDC.put("serviceVersion", VERSION.trim());
        }
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            LOGGER.error("Unhandled HTTP request failure", e);
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = failure == null || httpResponse.getStatus() >= 500
                    ? httpResponse.getStatus() : 500;
            MDC.put("status", Integer.toString(status));
            MDC.put("durationMs", Long.toString(durationMs));
            if (status >= 500) {
                LOGGER.error("HTTP request completed");
            } else if (status >= 400) {
                LOGGER.warn("HTTP request completed");
            } else {
                LOGGER.info("HTTP request completed");
            }
            MDC.clear();
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    @Override
    public void destroy() {
        // Shutdown is handled once by LexOApplicationLifecycle.
    }

    static String requestId(String candidate) {
        if (candidate != null && REQUEST_ID_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
    }

}
