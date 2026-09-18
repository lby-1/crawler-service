package com.jpwise.crawler.engine.handler.site.gxggzy;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 广西公共资源交易中心 - 招标项目计划处理器
 * 列表：API引擎
 * 详情：HTML���面（div.ewb-details-info）
 * 正文格式：中文编号段落（一、项目名称：XXX 二、招标编号：XXX ...）
 */
@Slf4j
@Component
public class GxggzyZbjhHandler implements CrawlHandler {

    private static final String[] SITE_CODES = {"gxggzy", "gxggzy-slgc"};
    // 交易公告(43231) + 招标项目计划(252106)
    private static final String[] CATEGORY_CODES = {"43231", "252106"};

    @Autowired
    private ApiCrawlEngine apiCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) {
                if (categoryCode == null) return false;
                for (String cat : CATEGORY_CODES) {
                    if (categoryCode.contains(cat)) return true;
                }
            }
        }
        return false;
    }

    @Override
    public String getHandlerName() {
        return "广西交易中心-交易公告处理器";
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getName(), maxPages);
        List<CrawlItem> items = apiCrawlEngine.crawlList(site, category, maxPages, reqInterval);
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());
        fetchHtmlDetail(item, reqInterval);
        log.info("[{}] 详情获取完成: 项目={}, 招标人={}, 联系人={}, 电话={}",
            getHandlerName(), item.getProjectName(), item.getTenderer(),
            item.getTenderContact(), item.getTenderTel());
    }

    private void fetchHtmlDetail(CrawlItem item, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) return;
        try {
            if (reqInterval > 0) Thread.sleep(reqInterval * 1000L);

            Document doc = Jsoup.connect(item.getSourceUrl())
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000).maxBodySize(0).followRedirects(true).get();

            Element contentEl = doc.selectFirst("div.ewb-details-info");
            if (contentEl == null) {
                log.warn("[{}] 未找到正文容器 div.ewb-details-info", getHandlerName());
                return;
            }

            String textContent = contentEl.text();
            item.setDetail(textContent);
            item.setSummary(textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent);

            // 从表格提取
            GxggzyExtractUtils.extractFromTable(item, contentEl);
            // 从纯文本提取
            GxggzyExtractUtils.extractFromPlainText(item, textContent);

            // 项目名称 fallback: 从标题提取
            if (item.getProjectName() == null && item.getTitle() != null) {
                String title = item.getTitle();
                for (String suffix : List.of("招标项目计划", "招标公告", "资格预审公告")) {
                    int idx = title.indexOf(suffix);
                    if (idx > 0) { title = title.substring(0, idx).trim(); break; }
                }
                if (!title.isBlank()) item.setProjectName(title);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[{}] 详情获取异常: {} - {}", getHandlerName(), item.getTitle(), e.getMessage());
        }
    }
}
