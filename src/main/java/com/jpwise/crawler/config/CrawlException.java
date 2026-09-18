package com.jpwise.crawler.config;

import lombok.Getter;

/**
 * 爬虫业务异常
 */
@Getter
public class CrawlException extends RuntimeException {

    private final int code;

    public CrawlException(String message) {
        super(message);
        this.code = 500;
    }

    public CrawlException(int code, String message) {
        super(message);
        this.code = code;
    }

    public CrawlException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }
}
