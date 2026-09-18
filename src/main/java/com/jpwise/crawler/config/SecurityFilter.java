package com.jpwise.crawler.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Token 鉴权过滤器
 * /api/health 不校验，其他 /api/* 接口需要 X-Crawler-Token 校验
 */
@Slf4j
@Component
@Order(1)
public class SecurityFilter implements Filter {

    @Autowired
    private CrawlerProperties properties;

    private static final Set<String> WHITELIST = Set.of("/api/health", "/api/crawl/test", "/api/crawl/categories",
            "/api/crawl/test/detail", "/api/results/all");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String path = httpReq.getRequestURI();

        // 白名单放行（测试阶段）
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            chain.doFilter(request, response);
            return;
        }

        // 非 API 路径放行
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // Token 校验
        String token = httpReq.getHeader("X-Crawler-Token");
        if (properties.getAuthToken() != null && !properties.getAuthToken().isBlank()
                && !properties.getAuthToken().equals(token)) {
            HttpServletResponse httpRes = (HttpServletResponse) response;
            httpRes.setStatus(401);
            httpRes.setContentType("application/json;charset=UTF-8");
            httpRes.getWriter().write("{\"code\":401,\"msg\":\"Token校验失败\"}");
            log.warn("【安全】Token校验失败: path={}, method={}, ip={}, token={}",
                    path, httpReq.getMethod(), getClientIp(httpReq),
                    token == null ? "null" : token.substring(0, Math.min(8, token.length())) + "***");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
