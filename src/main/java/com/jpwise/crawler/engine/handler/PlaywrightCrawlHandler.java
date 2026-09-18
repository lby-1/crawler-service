package com.jpwise.crawler.engine.handler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Playwright 爬取处理器适配器
 * 将现有的 PlaywrightCrawlEngine 适配到新的处理器架构
 */
@Slf4j
@Component
public class PlaywrightCrawlHandler {

    @Autowired
    private com.jpwise.crawler.engine.PlaywrightCrawlEngine playwrightEngine;

    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                     CrawlerProperties.CategoryConfig category,
                                     int maxPages, int reqInterval) {
        try {
            return playwrightEngine.crawlList(site, category, maxPages, reqInterval);
        } catch (Exception e) {
            log.error("Playwright 列表爬取失败: {} - {}: {}", 
                site.getName(), category.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        try {
            playwrightEngine.crawlDetail(item, site, reqInterval);
        } catch (Exception e) {
            log.error("Playwright 详情爬取失败: {}: {}", item.getTitle(), e.getMessage());
        }
    }
}
