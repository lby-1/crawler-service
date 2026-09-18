package com.jpwise.crawler.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jpwise.crawler.entity.CrawlLogEntity;
import com.jpwise.crawler.entity.CrawlResultEntity;
import com.jpwise.crawler.mapper.CrawlLogMapper;
import com.jpwise.crawler.mapper.CrawlResultMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @Autowired
    private CrawlResultMapper resultMapper;
    @Autowired
    private CrawlLogMapper logMapper;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        long pendingResults = resultMapper.selectCount(
                Wrappers.<CrawlResultEntity>lambdaQuery().eq(CrawlResultEntity::getSynced, 0));
        long pendingLogs = logMapper.selectCount(
                Wrappers.<CrawlLogEntity>lambdaQuery().eq(CrawlLogEntity::getSynced, 0));
        long totalResults = resultMapper.selectCount(null);

        return Map.of(
                "code", 200,
                "data", Map.of(
                        "status", "ok",
                        "version", "1.0.0",
                        "totalResults", totalResults,
                        "pendingResults", pendingResults,
                        "pendingLogs", pendingLogs
                )
        );
    }
}
