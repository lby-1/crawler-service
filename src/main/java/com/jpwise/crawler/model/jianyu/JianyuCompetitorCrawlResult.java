package com.jpwise.crawler.model.jianyu;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JianyuCompetitorCrawlResult {

    private String jobId;
    private boolean success = true;
    private int competitorCount;
    private int listCount;
    private int detailCount;
    private int insertedCount;
    private int updatedCount;
    private int skippedCount;
    private int errorCount;
    private long costMs;
    private List<String> errors = new ArrayList<>();

    public void addError(String message, int maxSize) {
        success = false;
        errorCount++;
        if (message != null && errors.size() < maxSize) {
            errors.add(message);
        }
    }
}
