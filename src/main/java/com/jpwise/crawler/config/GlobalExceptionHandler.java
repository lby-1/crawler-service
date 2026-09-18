package com.jpwise.crawler.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 全局异常处理器 — 统一 Controller 层错误返回格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常（爬取失败、配置错误等）
     */
    @ExceptionHandler(CrawlException.class)
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> handleCrawlException(CrawlException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Map.of("code", e.getCode(), "msg", e.getMessage());
    }

    /**
     * 请求参数缺失
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("参数缺失: {}", e.getParameterName());
        return Map.of("code", 400, "msg", "缺少参数: " + e.getParameterName());
    }

    /**
     * 参数类型错误
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {} = {}", e.getName(), e.getValue());
        return Map.of("code", 400, "msg", "参数类型错误: " + e.getName());
    }

    /**
     * 数据库异常
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleDataAccessException(org.springframework.dao.DataAccessException e) {
        log.error("数据库异常: {}", e.getMessage(), e);
        return Map.of("code", 500, "msg", "数据库操作失败，请稍后重试");
    }

    /**
     * 兜底：未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        log.error("未知异常: {}", e.getMessage(), e);
        return Map.of("code", 500, "msg", "服务器内部错误: " + e.getMessage());
    }
}
