package com.jpwise.crawler.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jpwise.crawler.entity.CrawlResultEntity;
import com.jpwise.crawler.mapper.CrawlResultMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 采集结果查询接口（供 JPwise 轮询拉取）
 */
@Slf4j
@RestController
@RequestMapping("/api/results")
public class ResultController {

    @Autowired
    private CrawlResultMapper resultMapper;

    /**
     * 拉取待同步结果（synced=0）
     */
    @GetMapping("/pending")
    public Map<String, Object> getPending(@RequestParam(defaultValue = "200") int limit) {
        Page<CrawlResultEntity> page = new Page<>(1, limit);
        Page<CrawlResultEntity> result = resultMapper.selectPage(page,
                Wrappers.<CrawlResultEntity>lambdaQuery()
                        .eq(CrawlResultEntity::getSynced, 0)
                        .orderByDesc(CrawlResultEntity::getCollectTime));

        return Map.of(
                "code", 200,
                "data", Map.of(
                        "hasMore", result.getTotal() > limit,
                        "total", result.getTotal(),
                        "results", result.getRecords()
                )
        );
    }

    /**
     * 确认结果已拉取
     */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.get("resultIds");
        if (ids == null || ids.isEmpty()) {
            return Map.of("code", 400, "msg", "resultIds不能为空");
        }

        resultMapper.update(null,
                Wrappers.<CrawlResultEntity>lambdaUpdate()
                        .in(CrawlResultEntity::getId, ids)
                        .set(CrawlResultEntity::getSynced, 1));

        return Map.of("code", 200, "msg", "confirmed", "data", Map.of("confirmedCount", ids.size()));
    }

    /**
     * 查看所有结果（调试用）
     */
    @GetMapping("/all")
    public Map<String, Object> getAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CrawlResultEntity> result = resultMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<CrawlResultEntity>lambdaQuery()
                        .orderByDesc(CrawlResultEntity::getCollectTime));

        return Map.of("code", 200, "data", Map.of(
                "total", result.getTotal(),
                "pages", result.getPages(),
                "current", result.getCurrent(),
                "records", result.getRecords()
        ));
    }
}
