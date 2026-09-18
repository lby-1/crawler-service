package com.jpwise.crawler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.ApiCrawlEngine;
import com.jpwise.crawler.model.CrawlItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 广西公共资源交易中心 API 测试 */
public class GxApiTest {
    public static void main(String[] args) {
        ApiCrawlEngine engine = new ApiCrawlEngine();

        CrawlerProperties.SiteConfig site = new CrawlerProperties.SiteConfig();
        site.setName("广西公共资源交易中心-水利工程");
        site.setBaseUrl("http://gxggzy.gxzf.gov.cn");
        site.setEngineType("api");

        CrawlerProperties.ApiConfig api = new CrawlerProperties.ApiConfig();
        api.setApiUrl("http://gxggzy.gxzf.gov.cn/irs/front/list");
        api.setPageSize(5);
        api.setRequestBody("{\"code\":\"181aedab55d\",\"tableName\":\"t_187973f42ee\",\"searchFields\":[{\"fieldName\":\"f_2023419644732\",\"searchWord\":\"\",\"withHighLight\":true}],\"sorts\":[{\"sortField\":\"save_time\",\"sortOrder\":\"DESC\"}],\"trackTotalHits\":true,\"granularity\":\"ALL\",\"pageNo\":{pageNo},\"pageSize\":{pageSize},\"customFilter\":{\"operator\":\"and\",\"properties\":[],\"filters\":[{\"operator\":\"or\",\"properties\":[{categoryCode}]}]}}");
        api.setDataPath("data.list");
        api.setTotalPath("data.pager.total");

        Map<String, String> fm = new HashMap<>();
        fm.put("title", "f_2023419644732");
        fm.put("publishTime", "save_time");
        fm.put("noticeId", "rec_id");
        fm.put("detailUrl", "{doc_pub_url}");
        api.setFieldMapping(fm);
        site.setApiConfig(api);

        // 测试交易公告
        CrawlerProperties.CategoryConfig cat = new CrawlerProperties.CategoryConfig();
        cat.setName("交易公告");
        cat.setCode("{\"property\":\"channel_id\",\"operator\":\"eq\",\"value\":\"43231\"}");

        System.out.println("=== 广西公共资源交易中心 - 水利工程/交易公告 ===");
        List<CrawlItem> items = engine.crawlList(site, cat, 1, 0);
        System.out.println("爬取到 " + items.size() + " 条:");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            CrawlItem item = items.get(i);
            System.out.printf("  [%d] %s%n      时间: %s | 链接: %s%n",
                    i + 1, item.getTitle(), item.getPublishTime(), item.getSourceUrl());
        }

        System.out.println("\n=== 测试完成 ===");
    }
}
