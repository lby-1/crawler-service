package com.jpwise.crawler.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CrawlResult {
    private String jobId;
    private String siteName;
    private String category;
    private int totalCount;
    private int newCount;
    private long costMs;
    private String errorMsg;
    private boolean success = true;
    private List<CrawlItem> items = new ArrayList<>();
}
