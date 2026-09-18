package com.jpwise.crawler.controller;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlResult;
import com.jpwise.crawler.model.JobStatus;
import com.jpwise.crawler.model.jianyu.JianyuCompetitorCrawlRequest;
import com.jpwise.crawler.model.jianyu.JianyuCompetitorCrawlResult;
import com.jpwise.crawler.service.CrawlExecutorService;
import com.jpwise.crawler.service.jianyu.JianyuCompetitorCrawlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    @Autowired
    private CrawlExecutorService crawlExecutorService;
    @Autowired
    private CrawlerProperties properties;
    @Autowired
    private JianyuCompetitorCrawlService jianyuCompetitorCrawlService;

    /**
     * 快速测试：爬取指定分类的列表（不获取详情）
     * GET /api/crawl/test?category=zbgg&pages=1
     */
    @GetMapping("/test")
    public Map<String, Object> testCrawl(
            @RequestParam(defaultValue = "zbgg") String category,
            @RequestParam(defaultValue = "1") int pages) {

        var pair = crawlExecutorService.findCategory(category);
        if (pair == null) {
            return Map.of("code", 400, "msg", "未找到分类: " + category, "data", getAvailableCategories());
        }

        CrawlResult result = crawlExecutorService.quickCrawl(pair.site(), pair.category(), pages);
        return buildResponse(result);
    }

    /**
     * 快速测试：爬取指定分类并获取详情
     * GET /api/crawl/test/detail?category=zbgg&pages=1
     */
    @GetMapping("/test/detail")
    public Map<String, Object> testCrawlWithDetail(
            @RequestParam(defaultValue = "zbgg") String category,
            @RequestParam(defaultValue = "1") int pages) {

        var pair = crawlExecutorService.findCategory(category);
        if (pair == null) {
            return Map.of("code", 400, "msg", "未找到分类: " + category);
        }

        CrawlResult result = crawlExecutorService.executeCrawl(
                pair.site(), pair.category(), pages, properties.getDefaultReqInterval(), true);
        return buildResponse(result);
    }

    /**
     * 全量异步爬取所有站点所有分类
     * POST /api/crawl/execute/all?pages=2&detail=false
     */
    @PostMapping("/execute/all")
    public Map<String, Object> executeAll(
            @RequestParam(defaultValue = "2") int pages,
            @RequestParam(defaultValue = "false") boolean detail) {
        crawlExecutorService.executeAllCategories(pages, detail);
        return Map.of("code", 200, "msg", "已提交异步执行");
    }

    /**
     * 通过剑鱼接口爬取外网竞争对手中标/投标情况，并写入 biz_competitor_info。
     * POST /api/crawl/jianyu/competitors
     */
    @PostMapping("/jianyu/competitors")
    public Map<String, Object> crawlJianyuCompetitors(
            @RequestBody(required = false) JianyuCompetitorCrawlRequest request) {
        JianyuCompetitorCrawlResult result = jianyuCompetitorCrawlService.crawl(request);
        return Map.of(
                "code", result.isSuccess() ? 200 : 500,
                "msg", result.isSuccess() ? "success" : "剑鱼竞争对手爬取失败",
                "data", result
        );
    }

    @GetMapping("/status/{jobId}")
    public Map<String, Object> getStatus(@PathVariable String jobId) {
        JobStatus status = crawlExecutorService.getJobStatus(jobId);
        if (status == null) return Map.of("code", 404, "msg", "任务不存在");
        return Map.of("code", 200, "data", status);
    }

    /**
     * 查看所有已配置的站点和分类
     */
    /**
     * 获取单条详情（供 JPwise "获取详情" 功能调用）
     * POST /api/crawl/fetchDetail
     * body: {"sourceUrl": "https://...", "siteName": "广州公共资源交易中心"}
     */
    @PostMapping("/fetchDetail")
    public Map<String, Object> fetchDetail(@RequestBody Map<String, String> body) {
        String sourceUrl = body.get("sourceUrl");
        String siteName = body.get("siteName");

        if (sourceUrl == null || sourceUrl.isBlank()) {
            return Map.of("code", 400, "msg", "sourceUrl不能为空");
        }

        try {
            Map<String, String> detail = crawlExecutorService.fetchDetail(sourceUrl, siteName);
            return Map.of("code", 200, "data", detail);
        } catch (Exception e) {
            log.error("获取详情失败: url={}, site={}", sourceUrl, siteName, e);
            return Map.of("code", 500, "msg", "获取详情失败: " + e.getMessage());
        }
    }

    @GetMapping("/categories")
    public Map<String, Object> getCategories() {
        return Map.of("code", 200, "data", getAvailableCategories());
    }

    private List<Map<String, String>> getAvailableCategories() {
        List<Map<String, String>> list = new ArrayList<>();
        if (properties.getSites() == null) return list;
        for (var entry : properties.getSites().entrySet()) {
            var site = entry.getValue();
            if (site.getCategories() == null) continue;
            for (var cat : site.getCategories()) {
                list.add(Map.of(
                        "siteKey", entry.getKey(),
                        "siteName", site.getName(),
                        "code", cat.getCode(),
                        "name", cat.getName(),
                        "url", cat.getUrl()
                ));
            }
        }
        return list;
    }

    private Map<String, Object> buildResponse(CrawlResult result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", result.isSuccess() ? 200 : 500);
        resp.put("msg", result.isSuccess() ? "success" : result.getErrorMsg());
        resp.put("siteName", result.getSiteName());
        resp.put("category", result.getCategory());
        resp.put("totalCount", result.getTotalCount());
        resp.put("newCount", result.getNewCount());
        resp.put("costMs", result.getCostMs());
        resp.put("items", result.getItems());
        return resp;
    }
}
