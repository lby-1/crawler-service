package com.jpwise.crawler.engine.handler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;

import java.util.List;

/**
 * 站点分类爬取处理器接口
 * 每个站点分类可以实现自己的处理器，实现完全隔离
 */
public interface CrawlHandler {

    /**
     * 判断是否支持该站点和分类
     * @param siteCode 站点代码（如 gzggzy-slsw）
     * @param categoryCode 分类代码（如 zbgg）
     * @return 是否支持
     */
    boolean supports(String siteCode, String categoryCode);

    /**
     * 爬取列表页
     * @param site 站点配置
     * @param category 分类配置
     * @param maxPages 最大页数
     * @param reqInterval 请求间隔（秒）
     * @return 爬取到的列表项
     */
    List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                              CrawlerProperties.CategoryConfig category,
                              int maxPages, int reqInterval);

    /**
     * 爬取详情页
     * @param item 列表项（包含 sourceUrl）
     * @param site 站点配置
     * @param reqInterval 请求间隔（秒）
     */
    void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval);

    /**
     * 获取处理器名称（用于日志）
     * @return 处理器名称
     */
    default String getHandlerName() {
        return this.getClass().getSimpleName();
    }
}
