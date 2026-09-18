package com.jpwise.crawler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.model.CrawlItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立测试 API 爬取引擎 — 广东省公共资源交易平台
 */
public class ApiCrawlEngineTest {

    public static void main(String[] args) {
        ApiCrawlEngine engine = new ApiCrawlEngine();

        // 构造站点配置
        CrawlerProperties.SiteConfig site = new CrawlerProperties.SiteConfig();
        site.setName("广东省公共资源交易平台-水利工程");
        site.setBaseUrl("https://ygp.gdzwfw.gov.cn");
        site.setEngineType("api");

        CrawlerProperties.ApiConfig apiConfig = new CrawlerProperties.ApiConfig();
        apiConfig.setApiUrl("https://ygp.gdzwfw.gov.cn/ggzy-portal/search/v2/items");
        apiConfig.setPageSize(10);
        apiConfig.setRequestBody("{\"type\":\"trading-type\",\"secondType\":\"A\",\"projectType\":\"A07\",\"pageNo\":{pageNo},\"pageSize\":{pageSize},\"siteCode\":\"44\",\"tradingProcess\":\"{categoryCode}\"}");
        apiConfig.setDataPath("data.pageData");
        apiConfig.setTotalPath("data.total");

        Map<String, String> fieldMapping = new HashMap<>();
        fieldMapping.put("title", "noticeTitle");
        fieldMapping.put("publishTime", "publishDate");
        fieldMapping.put("noticeId", "noticeId");
        fieldMapping.put("projectOwner", "projectOwner");
        fieldMapping.put("siteName", "siteName");
        fieldMapping.put("datasetName", "datasetName");
        fieldMapping.put("detailUrl", "https://ygp.gdzwfw.gov.cn/44/jygg/{edition}/{projectCode}/A/{tradingProcess}");
        apiConfig.setFieldMapping(fieldMapping);
        site.setApiConfig(apiConfig);

        // 分类：全部水利工程公告
        CrawlerProperties.CategoryConfig cat = new CrawlerProperties.CategoryConfig();
        cat.setName("全部水利工程公告");
        cat.setCode("");
        cat.setUrl("");

        System.out.println("=== 测试: 广东省公共资源交易平台 - 水利工程（全部公告，第1页） ===");
        List<CrawlItem> items = engine.crawlList(site, cat, 1, 1);
        System.out.println("爬取到 " + items.size() + " 条数据:");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            CrawlItem item = items.get(i);
            System.out.printf("  [%d] %s%n      时间: %s | 摘要: %s%n      链接: %s%n",
                    i + 1, item.getTitle(), item.getPublishTime(),
                    item.getSummary() != null && item.getSummary().length() > 80
                            ? item.getSummary().substring(0, 80) + "..." : item.getSummary(),
                    item.getSourceUrl());
        }

        // 测试招标公告分类
        CrawlerProperties.CategoryConfig cat2 = new CrawlerProperties.CategoryConfig();
        cat2.setName("招标公告");
        cat2.setCode("3C11,3C12,3C13,3C14");

        System.out.println("\n=== 测试: 水利工程-招标公告 ===");
        List<CrawlItem> items2 = engine.crawlList(site, cat2, 1, 1);
        System.out.println("爬取到 " + items2.size() + " 条数据:");
        for (int i = 0; i < Math.min(3, items2.size()); i++) {
            System.out.printf("  [%d] %s | %s%n", i + 1, items2.get(i).getTitle(), items2.get(i).getPublishTime());
        }

        System.out.println("\n=== 全部测试完成 ===");
    }
}
