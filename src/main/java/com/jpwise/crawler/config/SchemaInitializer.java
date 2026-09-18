package com.jpwise.crawler.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时检查表是否存在
 * 达梦：手动执行 schema-dm.sql 建表
 * MySQL：手动执行 schema.sql 建表
 */
@Slf4j
@Component
public class SchemaInitializer implements ApplicationRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 兼容 MySQL 和 达梦：直接查询表，能查到说明表存在
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_collect_result", Long.class);
            log.info("数据库表已存在，跳过初始化");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("doesn't exist") || msg.contains("不存在") || msg.contains("not found"))) {
                log.warn("===========================================");
                log.warn("  t_collect_result 表不存在！");
                log.warn("  达梦环境请执行: schema-dm.sql");
                log.warn("  MySQL环境请执行: schema.sql");
                log.warn("===========================================");
            } else {
                log.warn("数据库连接检查失败: {}（服务仍可启动，爬取引擎可独立使用）", msg);
            }
        }
    }
}
