package com.jpwise.crawler.scheduler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlResult;
import com.jpwise.crawler.service.CrawlExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz 定时爬取任务
 * 异常处理原则：
 *   - 单个站点/分类失败不影响其他站点继续爬取
 *   - 任何异常不抛出 JobExecutionException，避免 Quartz 停止调度
 *   - 所有异常均记录日志，包含站点名和分类名便于排查
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class CrawlScheduler implements Job {

    @Autowired
    private CrawlExecutorService crawlExecutorService;
    @Autowired
    private CrawlerProperties properties;

    @Override
    public void execute(JobExecutionContext context) {
        if (!properties.getSchedule().isEnabled()) {
            log.debug("定时爬取已禁用，跳过");
            return;
        }

        log.info("========== 定时爬取任务开始 ==========");
        long start = System.currentTimeMillis();
        int totalItems = 0, totalNew = 0, failedCount = 0;

        if (properties.getSites() == null || properties.getSites().isEmpty()) {
            log.warn("未配置爬取站点，跳过");
            return;
        }

        for (var entry : properties.getSites().entrySet()) {
            String siteKey = entry.getKey();
            CrawlerProperties.SiteConfig site = entry.getValue();
            if (site.getCategories() == null || site.getCategories().isEmpty()) continue;

            for (CrawlerProperties.CategoryConfig cat : site.getCategories()) {
                try {
                    log.info("定时爬取: [{}] {} - {}", siteKey, site.getName(), cat.getName());
                    CrawlResult result = crawlExecutorService.executeCrawl(
                            site, cat, site.getMaxPages(), properties.getDefaultReqInterval(), true);

                    totalItems += result.getTotalCount();
                    totalNew += result.getNewCount();

                    if (!result.isSuccess()) {
                        failedCount++;
                        log.warn("定时爬取部分失败: [{}] {} - {} : {}",
                                siteKey, site.getName(), cat.getName(), result.getErrorMsg());
                    }

                    // 分类间隔
                    Thread.sleep(properties.getDefaultReqInterval() * 1000L);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("定时爬取被中断，退出本轮调度");
                    return;
                } catch (Exception e) {
                    // 捕获所有异常，确保不影响其他站点/分类
                    failedCount++;
                    log.error("定时爬取异常: [{}] {} - {} : {}",
                            siteKey, site.getName(), cat.getName(), e.getMessage(), e);
                }
            }

            // 站点间增加额外间隔
            try {
                Thread.sleep(properties.getDefaultReqInterval() * 2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        long cost = System.currentTimeMillis() - start;
        log.info("========== 定时爬取完成: 总{}条, 新增{}条, 失败{}个分类, 耗时{}ms ==========",
                totalItems, totalNew, failedCount, cost);
    }
}
