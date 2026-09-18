package com.jpwise.crawler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.engine.CrawlEngine;
import com.jpwise.crawler.model.CrawlItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 全站点测试 */
public class AllSitesTest {
    public static void main(String[] args) {
        ApiCrawlEngine apiEngine = new ApiCrawlEngine();
        CrawlEngine htmlEngine = new CrawlEngine();

        // === 1. 云南 - API ===
        System.out.println("========== 云南省公共资源交易中心 - 水利工程/招标公告 ==========");
        CrawlerProperties.SiteConfig ynSite = new CrawlerProperties.SiteConfig();
        ynSite.setName("云南省公共资源交易中心-水利工程");
        ynSite.setBaseUrl("https://ggzy.yn.gov.cn");
        ynSite.setEngineType("api");
        CrawlerProperties.ApiConfig ynApi = new CrawlerProperties.ApiConfig();
        ynApi.setApiUrl("https://ggzy.yn.gov.cn/ynggfwpt-home-api/jyzyCenter/jyInfo/gcjs/{categoryCode}");
        ynApi.setPageSize(5);
        ynApi.setRequestBody("{\"pageNum\":{pageNo},\"pageSize\":{pageSize},\"hyId\":\"A07\"}");
        ynApi.setDataPath("value.list");
        ynApi.setTotalPath("value.total");
        Map<String, String> ynFm = new HashMap<>();
        ynFm.put("title", "bulletinname");
        ynFm.put("publishTime", "bulletinissuetime");
        ynFm.put("noticeId", "guid");
        ynFm.put("detailUrl", "https://ggzy.yn.gov.cn/tradeHall/tradeDetail?guid={guid}&tradeType=gcjs&tab={categoryCode}");
        ynApi.setFieldMapping(ynFm);
        ynSite.setApiConfig(ynApi);

        CrawlerProperties.CategoryConfig ynCat = new CrawlerProperties.CategoryConfig();
        ynCat.setName("招标公告");
        ynCat.setCode("getZbggList");
        List<CrawlItem> ynItems = apiEngine.crawlList(ynSite, ynCat, 1, 0);
        printResult(ynItems, 3);

        // === 2. 海南 - HTML ===
        System.out.println("\n========== 海南公共资源交易中心 - 工程建设/招标公告 ==========");
        CrawlerProperties.SiteConfig hnSite = new CrawlerProperties.SiteConfig();
        hnSite.setName("海南公共资源交易中心-工程建设");
        hnSite.setBaseUrl("https://ggzy.hainan.gov.cn");
        hnSite.setPagePattern("index.jhtml");
        CrawlerProperties.ListSelectors hnList = new CrawlerProperties.ListSelectors();
        hnList.setRow("table tr");
        hnList.setTitle("td a[title]");
        hnList.setTitleAttr("attr:title");
        hnList.setLink("");
        hnList.setLinkAttr("href");
        hnList.setDate("td[align=center]:last-of-type");
        hnSite.setListSelectors(hnList);

        CrawlerProperties.CategoryConfig hnCat = new CrawlerProperties.CategoryConfig();
        hnCat.setName("招标公告");
        hnCat.setCode("jgzbgg");
        hnCat.setUrl("https://ggzy.hainan.gov.cn/ggzy/ggzy/jgzbgg/index.jhtml");
        List<CrawlItem> hnItems = htmlEngine.crawlList(hnSite, hnCat, 1, 1);
        printResult(hnItems, 3);

        System.out.println("\n========== 全部测试完成 ==========");
    }

    static void printResult(List<CrawlItem> items, int max) {
        System.out.println("爬取到 " + items.size() + " 条:");
        for (int i = 0; i < Math.min(max, items.size()); i++) {
            CrawlItem item = items.get(i);
            System.out.printf("  [%d] %s%n      时间: %s | 链接: %s%n",
                    i + 1, item.getTitle(), item.getPublishTime(),
                    item.getSourceUrl() != null && item.getSourceUrl().length() > 80
                            ? item.getSourceUrl().substring(0, 80) + "..." : item.getSourceUrl());
        }
    }
}
