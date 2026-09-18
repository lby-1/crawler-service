package com.jpwise.crawler.scheduler;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.jianyu.JianyuCompetitorCrawlResult;
import com.jpwise.crawler.service.jianyu.JianyuCompetitorCrawlService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 剑鱼竞争对手招投标数据定时爬取任务。
 * 竞争对手企业列表不做同步任务，每次执行时读取外网 biz_competitor_enterprise 的当前企业名称。
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class JianyuCompetitorCrawlScheduler implements Job {

    @Autowired
    private JianyuCompetitorCrawlService jianyuCompetitorCrawlService;
    @Autowired
    private CrawlerProperties properties;

    @Override
    public void execute(JobExecutionContext context) {
        CrawlerProperties.Jianyu jianyu = properties.getJianyu();
        if (jianyu == null || !jianyu.getSchedule().isEnabled()) {
            log.debug("剑鱼竞争对手招投标定时爬取未启用，跳过");
            return;
        }
        if (!jianyu.isEnabled()) {
            log.debug("剑鱼竞争对手爬取未启用，跳过");
            return;
        }

        log.info("========== 剑鱼竞争对手招投标定时爬取开始 ==========");
        JianyuCompetitorCrawlResult result = jianyuCompetitorCrawlService.crawl(null);
        if (result.isSuccess()) {
            log.info("========== 剑鱼竞争对手招投标定时爬取完成: 竞争对手{}个, 列表{}条, 详情{}条, 新增{}条, 更新{}条, 跳过{}条, 耗时{}ms ==========",
                    result.getCompetitorCount(), result.getListCount(), result.getDetailCount(),
                    result.getInsertedCount(), result.getUpdatedCount(), result.getSkippedCount(), result.getCostMs());
            return;
        }

        log.warn("========== 剑鱼竞争对手招投标定时爬取失败: 竞争对手{}个, 列表{}条, 详情{}条, 新增{}条, 更新{}条, 跳过{}条, 错误{}个, 耗时{}ms, errors={} ==========",
                result.getCompetitorCount(), result.getListCount(), result.getDetailCount(),
                result.getInsertedCount(), result.getUpdatedCount(), result.getSkippedCount(),
                result.getErrorCount(), result.getCostMs(), result.getErrors());
    }
}
