package com.jpwise.crawler.engine.handler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * 默认爬取处理器
 * 基于 CSS 选择器的通用爬取逻辑，适用于大多数站点
 * 站点/分类如果没有特定处理器，将使用此类
 */
@Slf4j
@Component
public class DefaultCrawlHandler implements CrawlHandler {

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int MAX_RETRY = 2;

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    );

    private final Random random = new Random();

    @Autowired(required = false)
    private PlaywrightCrawlHandler playwrightHandler;

    @Autowired(required = false)
    private com.jpwise.crawler.engine.ApiCrawlEngine apiCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        // 默认处理器支持所有未被特定处理器处理的站点
        // 实际使用时，工厂会优先查找特定处理器，找不到才使用默认
        return true;
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        // 根据引擎类型选择实现
        if ("api".equalsIgnoreCase(site.getEngineType())) {
            if (apiCrawlEngine == null) {
                log.error("API 引擎未初始化，无法爬取站点: {}", site.getName());
                return Collections.emptyList();
            }
            return apiCrawlEngine.crawlList(site, category, maxPages, reqInterval);
        }

        if ("playwright".equalsIgnoreCase(site.getEngineType())) {
            if (playwrightHandler == null) {
                log.error("Playwright 处理器未初始化，无法爬取站点: {}", site.getName());
                return Collections.emptyList();
            }
            return playwrightHandler.crawlList(site, category, maxPages, reqInterval);
        }

        // 默认使用 HTML 引擎
        return crawlListByHtml(site, category, maxPages, reqInterval);
    }

    /**
     * HTML 模式爬取列表
     */
    protected List<CrawlItem> crawlListByHtml(CrawlerProperties.SiteConfig site,
                                              CrawlerProperties.CategoryConfig category,
                                              int maxPages, int reqInterval) {
        List<CrawlItem> allItems = new ArrayList<>();
        CrawlerProperties.ListSelectors sel = site.getListSelectors();

        for (int page = 1; page <= Math.max(1, maxPages); page++) {
            String url = buildPageUrl(site, category, page);
            log.debug("[DefaultHandler] 访问第{}页: {}", page, url);

            try {
                Document doc = fetchDocument(url);
                if (doc == null) continue;

                List<CrawlItem> pageItems = parseListPage(doc, sel, site, category);
                log.info("[DefaultHandler] {} - {} 第{}页提取到 {} 条数据", 
                    site.getName(), category.getName(), page, pageItems.size());

                if (pageItems.isEmpty()) break;
                allItems.addAll(pageItems);

                if (reqInterval > 0) {
                    Thread.sleep(reqInterval * 1000L);
                }
            } catch (Exception e) {
                log.error("[DefaultHandler] 第{}页处理失败: {}", page, e.getMessage());
            }
        }

        return allItems;
    }

    /**
     * 解析列表页
     */
    protected List<CrawlItem> parseListPage(Document doc, 
                                            CrawlerProperties.ListSelectors sel,
                                            CrawlerProperties.SiteConfig site,
                                            CrawlerProperties.CategoryConfig category) {
        List<CrawlItem> items = new ArrayList<>();
        Elements rows = doc.select(sel.getRow());

        for (Element row : rows) {
            Element titleEl = row.selectFirst(sel.getTitle());
            if (titleEl == null) continue;

            CrawlItem item = new CrawlItem();
            String title = extractValue(titleEl, sel.getTitleAttr());
            if (title == null || title.isBlank()) {
                title = titleEl.text().trim();
            }
            item.setTitle(title);

            // 提取链接
            Element linkEl = (sel.getLink() != null && !sel.getLink().isBlank())
                    ? row.selectFirst(sel.getLink()) : titleEl;
            if (linkEl != null) {
                String href = linkEl.attr(sel.getLinkAttr());
                item.setSourceUrl(ensureAbsoluteUrl(href, site.getBaseUrl()));
            }

            // 提取日期
            if (sel.getDate() != null && !sel.getDate().isBlank()) {
                Element dateEl = row.selectFirst(sel.getDate());
                if (dateEl != null) {
                    item.setPublishTime(dateEl.text().trim());
                }
            }

            // 分类信息
            item.setCategory(category.getName());
            item.setCategoryCode(category.getCode());
            item.setSiteName(site.getName());
            item.setProvince(site.getProvince());
            item.setCity(site.getCity());

            // URL Hash
            if (item.getSourceUrl() != null) {
                item.setUrlHash(org.apache.commons.codec.digest.DigestUtils.md5Hex(item.getSourceUrl()));
            }

            items.add(item);
        }

        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
            return;
        }

        // API 引擎在 crawlList 时已经处理了详情 URL，不需要额外获取
        if ("api".equalsIgnoreCase(site.getEngineType())) {
            log.debug("[DefaultHandler] API 引擎跳过详情获取: {}", item.getTitle());
            return;
        }

        String detailUrl = ensureAbsoluteUrl(item.getSourceUrl(), site.getBaseUrl());

        try {
            if (reqInterval > 0) {
                Thread.sleep(reqInterval * 1000L);
            }

            // 根据引擎类型选择
            if ("playwright".equalsIgnoreCase(site.getEngineType()) && playwrightHandler != null) {
                playwrightHandler.crawlDetail(item, site, reqInterval);
                return;
            }

            crawlDetailByHtml(item, site, detailUrl);

        } catch (Exception e) {
            log.error("[DefaultHandler] 详情获取失败: {} - {}", item.getTitle(), e.getMessage());
        }
    }

    /**
     * HTML 模式爬取详情
     */
    protected void crawlDetailByHtml(CrawlItem item, CrawlerProperties.SiteConfig site, String detailUrl) 
            throws IOException {
        Document doc = fetchDocument(detailUrl);
        if (doc == null) return;

        CrawlerProperties.DetailSelectors sel = site.getDetailSelectors();

        // 提取正文
        if (sel.getContent() != null && !sel.getContent().isBlank()) {
            Element contentEl = doc.selectFirst(sel.getContent());
            if (contentEl != null) {
                item.setDetail(contentEl.text());
                item.setSummary(truncate(contentEl.text(), 500));
                extractStructuredFromTable(contentEl, item);
            }
        }

        // 提取发布时间
        if (sel.getPublishTime() != null && !sel.getPublishTime().isBlank()) {
            Element timeEl = doc.selectFirst(sel.getPublishTime());
            if (timeEl != null) {
                String timeText = extractValue(timeEl, sel.getPublishTimeAttr());
                if (timeText != null && !timeText.isBlank()) {
                    item.setPublishTime(timeText.length() > 19 ? timeText.substring(0, 19) : timeText);
                }
            }
        }

        item.setSourceUrl(detailUrl);
    }

    // ============ 工具方法 ============

    protected String buildPageUrl(CrawlerProperties.SiteConfig site, 
                                  CrawlerProperties.CategoryConfig category, int page) {
        if (page == 1) {
            return category.getUrl();
        }
        String pattern = site.getPagePattern();
        if (pattern != null && pattern.contains("{page}")) {
            return category.getUrl() + pattern.replace("{page}", String.valueOf(page));
        }
        return category.getUrl() + pattern;
    }

    protected Document fetchDocument(String url) throws IOException {
        String userAgent = USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));
        
        for (int retry = 0; retry < MAX_RETRY; retry++) {
            try {
                return Jsoup.connect(url)
                        .userAgent(userAgent)
                        .timeout(CONNECT_TIMEOUT)
                        .followRedirects(true)
                        .get();
            } catch (IOException e) {
                if (retry == MAX_RETRY - 1) throw e;
                log.warn("请求失败，第{}次重试: {}", retry + 1, url);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    protected String extractValue(Element el, String attr) {
        if (attr == null || attr.isBlank() || "text".equals(attr)) {
            return el.text().trim();
        }
        if (attr.startsWith("attr:")) {
            return el.attr(attr.substring(5)).trim();
        }
        return el.attr(attr).trim();
    }

    protected String ensureAbsoluteUrl(String url, String baseUrl) {
        if (url == null || url.isBlank()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return baseUrl + url;
        return baseUrl + "/" + url;
    }

    protected String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 从 HTML 表格中提取结构化字段
     * 子类可以重写此方法以实现特定的提取逻辑
     */
    protected void extractStructuredFromTable(Element contentEl, CrawlItem item) {
        // 基础实现，提取通用的标签-值对
        // 实际提取逻辑由子类或专门的提取器实现
    }
}
