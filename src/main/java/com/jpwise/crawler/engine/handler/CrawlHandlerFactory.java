package com.jpwise.crawler.engine.handler;

import com.jpwise.crawler.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 爬取处理器工厂
 * 根据站点代码和分类代码自动路由到对应的处理器
 * 
 * 优先级：
 * 1. 特定站点+特定分类处理器（如 GzggzyZbggHandler）
 * 2. 特定站点通用处理器（如果有）
 * 3. 默认处理器（DefaultCrawlHandler）
 */
@Slf4j
@Component
public class CrawlHandlerFactory {

    @Autowired
    private List<CrawlHandler> handlers;

    @Autowired
    private DefaultCrawlHandler defaultHandler;

    @PostConstruct
    public void init() {
        log.info("初始化 CrawlHandlerFactory，共发现 {} 个处理器", handlers.size());
        handlers.forEach(h -> {
            if (!(h instanceof DefaultCrawlHandler)) {
                log.info("  注册处理器: {}", h.getHandlerName());
            }
        });
    }

    /**
     * 获取处理器
     * @param siteCode 站点代码
     * @param categoryCode 分类代码
     * @return 对应的处理器
     */
    public CrawlHandler getHandler(String siteCode, String categoryCode) {
        // 1. 查找特定处理器（精确匹配站点+分类）
        for (CrawlHandler handler : handlers) {
            if (handler.supports(siteCode, categoryCode) 
                && !(handler instanceof DefaultCrawlHandler)) {
                log.debug("使用特定处理器: {} for {}/{}", 
                    handler.getHandlerName(), siteCode, categoryCode);
                return handler;
            }
        }

        // 2. 使用默认处理器
        log.debug("使用默认处理器 for {}/{}", siteCode, categoryCode);
        return defaultHandler;
    }

    /**
     * 获取处理器（通过站点配置）
     */
    public CrawlHandler getHandler(CrawlerProperties.SiteConfig site, 
                                   CrawlerProperties.CategoryConfig category) {
        // 获取站点代码（从 sites Map 的 key）
        String siteCode = findSiteCode(site);
        return getHandler(siteCode, category.getCode());
    }

    /**
     * 获取所有注册的处理器列表
     */
    public List<CrawlHandler> getAllHandlers() {
        return new ArrayList<>(handlers);
    }

    /**
     * 查找站点代码
     * 通过站点名称反查配置中的 key
     */
    private String findSiteCode(CrawlerProperties.SiteConfig site) {
        // 实际使用时可以通过其他方式传递 siteCode
        // 这里简化处理，返回站点名称的简化版本
        if (site.getName() != null && site.getName().contains("广州")) {
            return "gzggzy-slsw";
        }
        return "unknown";
    }
}
