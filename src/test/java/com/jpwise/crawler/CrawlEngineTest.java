package com.jpwise.crawler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.CrawlEngine;
import com.jpwise.crawler.model.CrawlItem;

import java.util.List;

/**
 * 独立测试：不依赖 Spring 和数据库，直接测试可配置爬取引擎
 * 验证通过 CSS 选择器配置即可适配不同网站
 */
public class CrawlEngineTest {

    public static void main(String[] args) {
        CrawlEngine engine = new CrawlEngine();

        // 构造广州公共资源交易中心的站点配置（模拟从 yml 读取）
        CrawlerProperties.SiteConfig site = new CrawlerProperties.SiteConfig();
        site.setName("广州公共资源交易中心-水利水务");
        site.setBaseUrl("https://www.gzggzy.cn");
        site.setPagePattern("index.jhtml");

        CrawlerProperties.ListSelectors listSel = new CrawlerProperties.ListSelectors();
        listSel.setRow("table.public-table tr");
        listSel.setTitle("td a.title");
        listSel.setTitleAttr("attr:title");
        listSel.setLink("");
        listSel.setLinkAttr("href");
        listSel.setDate("td span");
        site.setListSelectors(listSel);

        CrawlerProperties.DetailSelectors detailSel = new CrawlerProperties.DetailSelectors();
        detailSel.setContent("div.detail-content-main");
        detailSel.setPublishTime("meta[name=PubDate]");
        detailSel.setPublishTimeAttr("attr:content");
        site.setDetailSelectors(detailSel);

        CrawlerProperties.CategoryConfig cat = new CrawlerProperties.CategoryConfig();
        cat.setName("招标公告");
        cat.setCode("zbgg");
        cat.setUrl("https://www.gzggzy.cn/jyywjsgcslswzbgg/index.jhtml");

        System.out.println("=== 测试1: 可配置引擎 - 爬取招标公告第1页 ===");
        List<CrawlItem> items = engine.crawlList(site, cat, 1, 1);
        System.out.println("爬取到 " + items.size() + " 条数据:");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            CrawlItem item = items.get(i);
            System.out.printf("  [%d] %s | %s%n", i + 1, item.getTitle(), item.getPublishTime());
        }

        if (!items.isEmpty()) {
            System.out.println("\n=== 测试2: 获取详情 ===");
            CrawlItem detail = engine.crawlDetail(items.get(0), site, 1);
            System.out.println("  标题: " + detail.getTitle());
            System.out.println("  时间: " + detail.getPublishTime());
            System.out.println("  链接: " + detail.getSourceUrl());
            String summary = detail.getSummary();
            if (summary != null) {
                System.out.println("  摘要: " + (summary.length() > 150 ? summary.substring(0, 150) + "..." : summary));
            }
        }

        // 测试中标候选人公示
        CrawlerProperties.CategoryConfig cat2 = new CrawlerProperties.CategoryConfig();
        cat2.setName("中标候选人公示");
        cat2.setCode("zbhxrgs");
        cat2.setUrl("https://www.gzggzy.cn/jyywjsgcslswzbhxrgs/index.jhtml");

        System.out.println("\n=== 测试3: 中标候选人公示第1页 ===");
        List<CrawlItem> items2 = engine.crawlList(site, cat2, 1, 1);
        System.out.println("爬取到 " + items2.size() + " 条数据:");
        for (int i = 0; i < Math.min(3, items2.size()); i++) {
            System.out.printf("  [%d] %s | %s%n", i + 1, items2.get(i).getTitle(), items2.get(i).getPublishTime());
        }

        System.out.println("\n=== 全部测试完成 ===");
    }
}
