package com.jpwise.crawler.engine.handler.site.hnggzy;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.CrawlEngine;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 海南公共资源交易中心 - 中标结果公告处理器
 * 列表：HTML引擎（CrawlEngine）
 * 详情：HTML页面（div.contnet_con）
 * 正文格式：纯文本，含"中标人"、"中标价"、"招标人"、"联系人"、"电话"等
 */
@Slf4j
@Component
public class HnggzyZbjgHandler implements CrawlHandler {

    private static final String[] SITE_CODES = {"hnggzy", "hnggzy-gcjs"};
    private static final String CATEGORY_CODE = "jgzbjggg";

    @Autowired
    private CrawlEngine crawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) return CATEGORY_CODE.equals(categoryCode);
        }
        return false;
    }

    @Override
    public String getHandlerName() { return "海南交易中心-中标结果公告处理器"; }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getName(), maxPages);
        List<CrawlItem> items = crawlEngine.crawlList(site, category, maxPages, reqInterval);
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());
        fetchHtmlDetail(item, site, reqInterval);
        log.info("[{}] 详情完成: 项目={}, 中标人={}, 中标价={}, 招标人={}, 联系人={}, 电话={}",
            getHandlerName(), item.getProjectName(), item.getBidWinner(), item.getBidAmount(),
            item.getTenderer(), item.getTenderContact(), item.getTenderTel());
    }

    private void fetchHtmlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) return;
        try {
            if (reqInterval > 0) Thread.sleep(reqInterval * 1000L);

            String detailUrl = item.getSourceUrl();
            if (detailUrl.startsWith("http://")) detailUrl = detailUrl.replace("http://", "https://");
            detailUrl = detailUrl.replace(":80/", "/");

            Document doc = Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000).maxBodySize(0).followRedirects(true).get();

            Element contentEl = doc.selectFirst("div.contnet_con");
            if (contentEl == null) {
                contentEl = doc.selectFirst("div.content_con, div.container");
                if (contentEl == null) {
                    log.warn("[{}] 未找到正文容器", getHandlerName());
                    return;
                }
            }

            String textContent = contentEl.text();
            item.setDetail(textContent);
            item.setSummary(textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent);

            // 提取发布时间
            Element timeEl = doc.selectFirst("meta[name=PubDate]");
            if (timeEl != null) {
                String pubDate = timeEl.attr("content");
                if (pubDate != null && !pubDate.isBlank()) {
                    item.setPublishTime(pubDate.length() > 19 ? pubDate.substring(0, 19) : pubDate);
                }
            }

            // 从表格提取
            HnggzyExtractUtils.extractFromTable(item, contentEl);
            // 从纯文本提取
            HnggzyExtractUtils.extractZbjgFields(item, textContent);

            item.setSourceUrl(detailUrl);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[{}] 详情获取异常: {} - {}", getHandlerName(), item.getTitle(), e.getMessage());
        }
    }
}
