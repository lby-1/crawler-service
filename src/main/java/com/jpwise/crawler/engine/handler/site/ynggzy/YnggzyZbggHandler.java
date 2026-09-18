package com.jpwise.crawler.engine.handler.site.ynggzy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 云南省公共资源交易中心 - 招标公告处理器
 * 列表API: getZbggList
 * 详情API: findZbggByGuid?guid=xxx
 * 特点：详情API返回丰富的结构化字段 + bulletincontent(HTML表格)
 */
@Slf4j
@Component
public class YnggzyZbggHandler implements CrawlHandler {

    private static final String[] SITE_CODES = {"ynggzy", "ynggzy-slgc"};
    private static final String CATEGORY_CODE = "getZbggList";
    private static final String DETAIL_API = "https://ggzy.yn.gov.cn/ynggfwpt-home-api/jyzyCenter/jyInfo/gcjs/findZbggByGuid?guid=";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    @Autowired
    private ApiCrawlEngine apiCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) return CATEGORY_CODE.equals(categoryCode);
        }
        return false;
    }

    @Override
    public String getHandlerName() { return "云南交易中心-招标公告处理器"; }

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
        fetchDetail(item, reqInterval);
        log.info("[{}] 详情完成: 项目={}, 标段={}, 限价={}, 开标时间={}, 地点={}, 招标人={}, 联系人={}, 电话={}",
            getHandlerName(), item.getProjectName(), item.getSectionName(), item.getMaxPrice(),
            item.getOpenTime(), item.getOpenPlace(), item.getTenderer(), item.getTenderContact(), item.getTenderTel());
    }

    private void fetchDetail(CrawlItem item, int reqInterval) {
        String guid = extractGuid(item);
        if (guid == null) return;
        try {
            if (reqInterval > 0) Thread.sleep(reqInterval * 1000L);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(DETAIL_API + guid))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            JSONObject json = JSON.parseObject(response.body());
            if (json == null || !"1".equals(json.getString("code"))) return;
            JSONObject value = json.getJSONObject("value");
            if (value == null) return;

            YnggzyExtractUtils.extractFromJson(item, value);
            String htmlContent = YnggzyExtractUtils.getHtmlContent(value);
            if (htmlContent != null && !htmlContent.isBlank()) {
                YnggzyExtractUtils.extractFromHtmlContent(item, htmlContent);
            }
            if (item.getProjectName() == null && item.getTitle() != null) {
                item.setProjectName(item.getTitle().replaceAll("招标公告$", "").trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[{}] 详情获取异常: {} - {}", getHandlerName(), item.getTitle(), e.getMessage());
        }
    }

    private String extractGuid(CrawlItem item) {
        if (item.getSourceUrl() == null) return null;
        String url = item.getSourceUrl();
        int idx = url.indexOf("guid=");
        if (idx >= 0) {
            String guid = url.substring(idx + 5);
            int amp = guid.indexOf('&');
            return amp > 0 ? guid.substring(0, amp) : guid;
        }
        return null;
    }
}
