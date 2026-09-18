package com.jpwise.crawler.scheduler;

import com.jpwise.crawler.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Quartz 调度配置
 * 根据 crawler.schedule.cron 配置定时触发 CrawlScheduler
 */
@Slf4j
@Configuration
public class QuartzConfig {

    @Autowired
    private CrawlerProperties properties;

    @Autowired
    private AutowiringSpringBeanJobFactory jobFactory;

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(
            @Qualifier("crawlTrigger") Trigger crawlTrigger,
            @Qualifier("jianyuCompetitorCrawlTrigger") Trigger jianyuCompetitorCrawlTrigger,
            @Qualifier("crawlJobDetail") JobDetail crawlJobDetail,
            @Qualifier("jianyuCompetitorCrawlJobDetail") JobDetail jianyuCompetitorCrawlJobDetail) {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setJobFactory(jobFactory);
        factory.setJobDetails(crawlJobDetail, jianyuCompetitorCrawlJobDetail);
        factory.setTriggers(crawlTrigger, jianyuCompetitorCrawlTrigger);
        factory.setOverwriteExistingJobs(true);
        return factory;
    }

    @Bean
    public JobDetail crawlJobDetail() {
        return JobBuilder.newJob(CrawlScheduler.class)
                .withIdentity("crawlJob", "crawlerGroup")
                .storeDurably()
                .build();
    }

    @Bean
    public JobDetail jianyuCompetitorCrawlJobDetail() {
        return JobBuilder.newJob(JianyuCompetitorCrawlScheduler.class)
                .withIdentity("jianyuCompetitorCrawlJob", "crawlerGroup")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger crawlTrigger(@Qualifier("crawlJobDetail") JobDetail crawlJobDetail) {
        String cron = properties.getSchedule().getCron();
        boolean enabled = properties.getSchedule().isEnabled();

        log.info("定时爬取配置: enabled={}, cron={}", enabled, cron);

        return TriggerBuilder.newTrigger()
                .forJob(crawlJobDetail)
                .withIdentity("crawlTrigger", "crawlerGroup")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    @Bean
    public Trigger jianyuCompetitorCrawlTrigger(
            @Qualifier("jianyuCompetitorCrawlJobDetail") JobDetail jianyuCompetitorCrawlJobDetail) {
        CrawlerProperties.JianyuSchedule schedule = properties.getJianyu().getSchedule();
        String cron = schedule.getCron();
        boolean enabled = schedule.isEnabled();

        log.info("剑鱼竞争对手招投标定时爬取配置: enabled={}, cron={}", enabled, cron);

        return TriggerBuilder.newTrigger()
                .forJob(jianyuCompetitorCrawlJobDetail)
                .withIdentity("jianyuCompetitorCrawlTrigger", "crawlerGroup")
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
