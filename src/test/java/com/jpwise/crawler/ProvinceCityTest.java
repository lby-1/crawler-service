package com.jpwise.crawler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.engine.CrawlEngine;
import com.jpwise.crawler.model.CrawlItem;
import java.util.*;

public class ProvinceCityTest {
    public static void main(String[] args) {
        // 云南API（city从jyptid取）
        ApiCrawlEngine apiEngine = new ApiCrawlEngine();
        CrawlerProperties.SiteConfig yn = new CrawlerProperties.SiteConfig();
        yn.setName("云南"); yn.setBaseUrl("https://ggzy.yn.gov.cn");
        yn.setProvince("云南"); yn.setCity(""); yn.setEngineType("api");
        CrawlerProperties.ApiConfig ynApi = new CrawlerProperties.ApiConfig();
        ynApi.setApiUrl("https://ggzy.yn.gov.cn/ynggfwpt-home-api/jyzyCenter/jyInfo/gcjs/{categoryCode}");
        ynApi.setPageSize(3);
        ynApi.setRequestBody("{\"pageNum\":{pageNo},\"pageSize\":{pageSize}}");
        ynApi.setDataPath("value.list"); ynApi.setTotalPath("value.total");
        Map<String,String> fm = new HashMap<>();
        fm.put("title","bulletinname"); fm.put("publishTime","bulletinissuetime");
        fm.put("noticeId","guid"); fm.put("city","jyptid");
        ynApi.setFieldMapping(fm); yn.setApiConfig(ynApi);
        CrawlerProperties.CategoryConfig cat = new CrawlerProperties.CategoryConfig();
        cat.setName("招标公告"); cat.setCode("getZbggList");

        System.out.println("=== 云南（省份固定，城市从API取） ===");
        for (CrawlItem i : apiEngine.crawlList(yn, cat, 1, 0))
            System.out.printf("  %s | %s | %s%n", i.getProvince(), i.getCity(), i.getTitle());

        // 广州HTML（省份城市都固定）
        CrawlEngine htmlEngine = new CrawlEngine();
        CrawlerProperties.SiteConfig gz = new CrawlerProperties.SiteConfig();
        gz.setName("广州"); gz.setBaseUrl("https://www.gzggzy.cn");
        gz.setProvince("广东"); gz.setCity("广州"); gz.setPagePattern("index.jhtml");
        CrawlerProperties.ListSelectors ls = new CrawlerProperties.ListSelectors();
        ls.setRow("table.public-table tr"); ls.setTitle("td a.title");
        ls.setTitleAttr("attr:title"); ls.setDate("td span"); gz.setListSelectors(ls);
        CrawlerProperties.CategoryConfig cat2 = new CrawlerProperties.CategoryConfig();
        cat2.setName("招标公告"); cat2.setCode("zbgg");
        cat2.setUrl("https://www.gzggzy.cn/jyywjsgcslswzbgg/index.jhtml");

        System.out.println("\n=== 广州（省份城市固定） ===");
        for (CrawlItem i : htmlEngine.crawlList(gz, cat2, 1, 0))
            System.out.printf("  %s | %s | %s%n", i.getProvince(), i.getCity(), i.getTitle());
    }
}
