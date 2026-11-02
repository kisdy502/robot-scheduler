package com.zioneer.robotscheduler.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 入站 HTTP 访问日志：低频端点（任务下发/取消/资源管理/地图上传）全量 INFO，
 * 定时轮询端点（大屏状态刷新/任务流终态轮询）按排除路径跳过。
 */
@Slf4j
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final String ATTR_START_NANOS = AccessLogInterceptor.class.getName() + ".startNanos";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> excludePaths;

    public AccessLogInterceptor(List<String> excludePaths) {
        this.excludePaths = List.copyOf(excludePaths);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        Object start = request.getAttribute(ATTR_START_NANOS);
        long costMillis = start instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : -1;
        if (response.getStatus() >= 400 || ex != null) {
            // 异常请求不排除：轮询端点报错也要可见（4xx/5xx 全量打）
            log.info("HTTP <- {} {}?{} -> {} cost={}ms{}", method(request), request.getRequestURI(),
                    request.getQueryString(), response.getStatus(), costMillis,
                    ex == null ? "" : " exception=" + ex.getMessage());
            return;
        }
        if (isExcluded(request)) {
            return;
        }
        log.info("HTTP <- {} {}?{} -> {} cost={}ms", method(request), request.getRequestURI(),
                request.getQueryString(), response.getStatus(), costMillis);
    }

    private boolean isExcluded(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String pattern : excludePaths) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private String method(HttpServletRequest request) {
        String method = request.getMethod();
        return method == null ? HttpMethod.GET.name() : method.toUpperCase();
    }
}
