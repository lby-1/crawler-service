package com.jpwise.crawler.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.engine.handler.CrawlHandlerFactory;
import com.jpwise.crawler.entity.CrawlLogEntity;
import com.jpwise.crawler.entity.CrawlResultEntity;
import com.jpwise.crawler.mapper.CrawlLogMapper;
import com.jpwise.crawler.mapper.CrawlResultMapper;
import com.jpwise.crawler.model.CrawlItem;
import com.jpwise.crawler.model.CrawlResult;
import com.jpwise.crawler.model.JobStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CrawlExecutorService {

    @Autowired
    private CrawlHandlerFactory handlerFactory;
    @Autowired
    private CrawlResultMapper resultMapper;
    @Autowired
    private CrawlLogMapper logMapper;
    @Autowired
    private CrawlerProperties properties;

    private final Map<String, JobStatus> jobStatusMap = new ConcurrentHashMap<>();

    /**
     * 执行单个分类的爬取（同步）
     * @param site     站点配置（含选择器）
     * @param category 分类配置
     */
    public CrawlResult executeCrawl(CrawlerProperties.SiteConfig site,
                                    CrawlerProperties.CategoryConfig category,
                                    int maxPages, int reqInterval, boolean fetchDetail) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        long startTime = System.currentTimeMillis();

        CrawlResult result = new CrawlResult();
        result.setJobId(jobId);
        result.setSiteName(site.getName());
        result.setCategory(category.getName());

        jobStatusMap.put(jobId, JobStatus.running(jobId, "正在爬取" + category.getName()));

        try {
            // 1. 通过工厂获取对应的处理器（根据站点代码和分类代码）
            String siteCode = findSiteCode(site);
            CrawlHandler handler = handlerFactory.getHandler(siteCode, category.getCode());
            log.info("使用处理器: {} for {}/{}", handler.getHandlerName(), siteCode, category.getCode());
            
            // 2. 使用处理器爬取列表
            List<CrawlItem> items = handler.crawlList(site, category, maxPages, reqInterval);
            result.setTotalCount(items.size());

            // 3. 可选：逐条获取详情
            if (fetchDetail) {
                for (int i = 0; i < items.size(); i++) {
                    jobStatusMap.put(jobId, JobStatus.running(jobId,
                            String.format("正在获取详情 %d/%d", i + 1, items.size())));
                    try {
                        // 使用处理器的详情爬取方法
                        handler.crawlDetail(items.get(i), site, reqInterval);
                        items.get(i).setSpiderStatus("已获取");
                    } catch (Exception detailEx) {
                        log.warn("详情获取失败(不影响入库): {} - {}", items.get(i).getTitle(), detailEx.getMessage());
                    }
                }
            }

            // 3. 去重并入库（数据库异常时降级：记录日志但不中断爬取结果返回）
            int newCount = 0;
            int duplicateCount = 0;
            boolean dbAvailable = true;
            for (CrawlItem item : items) {
                try {
                    if (item.getUrlHash() == null || item.getUrlHash().isBlank()) {
                        log.warn("条目缺少URL Hash，跳过: {}", item.getTitle());
                        continue;
                    }
                    if (!existsByUrlHash(item.getUrlHash())) {
                        saveResult(item);
                        newCount++;
                        log.debug("新增条目: {} | {}", item.getTitle(), item.getSourceUrl());
                    } else {
                        duplicateCount++;
                        log.debug("去重跳过(已存在): {} | hash={}", item.getTitle(), item.getUrlHash());
                    }
                } catch (org.springframework.dao.DataAccessException dbEx) {
                    if (dbAvailable) {
                        log.error("数据库入库失败，后续条目跳过入库: {}", dbEx.getMessage());
                        dbAvailable = false;
                    }
                }
            }
            if (duplicateCount > 0) {
                log.info("去重统计: {} 条重复(已存在), {} 条新增", duplicateCount, newCount);
            }
            result.setNewCount(newCount);
            result.setItems(items);

            long costMs = System.currentTimeMillis() - startTime;
            result.setCostMs(costMs);

            // 日志入库（DB不可用时跳过）
            if (dbAvailable) {
                try {
                    saveLog(jobId, site.getName(), category.getName(), category.getCode(),
                            items.size(), newCount, costMs, null);
                } catch (Exception logEx) {
                    log.error("日志入库失败: {}", logEx.getMessage());
                }
            } else {
                result.setErrorMsg("爬取成功但数据库入库失败");
            }

            jobStatusMap.put(jobId, JobStatus.done(jobId,
                    String.format("%s 完成: 爬取%d条, 新增%d条", category.getName(), items.size(), newCount)));

            log.info("爬取完成: {} - {} - 总{}条, 新增{}条, 耗时{}ms",
                    site.getName(), category.getName(), items.size(), newCount, costMs);

        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMsg(e.getMessage());
            long costMs = System.currentTimeMillis() - startTime;
            result.setCostMs(costMs);

            saveLog(jobId, site.getName(), category.getName(), category.getCode(),
                    0, 0, costMs, e.getMessage());
            jobStatusMap.put(jobId, JobStatus.failed(jobId, e.getMessage()));

            log.error("爬取失败: {} - {}", site.getName(), category.getName(), e);
        }

        return result;
    }

    /**
     * 顺序执行所有站点所有分类（无等待）
     */
    public void executeAllCategories(int maxPagesPerCategory, boolean fetchDetail) {
        // 获取所有站点列表
        List<Map.Entry<String, CrawlerProperties.SiteConfig>> siteEntries = new ArrayList<>(properties.getSites().entrySet());

        for (int i = 0; i < siteEntries.size(); i++) {
            Map.Entry<String, CrawlerProperties.SiteConfig> entry = siteEntries.get(i);
            CrawlerProperties.SiteConfig site = entry.getValue();

            if (site.getCategories() == null) continue;

            log.info("========== 开始爬取站点 {}/{}: {} ==========", i + 1, siteEntries.size(), site.getName());

            // 爬取该站点的所有分类
            for (CrawlerProperties.CategoryConfig cat : site.getCategories()) {
                log.info("开始爬取: {} - {}", site.getName(), cat.getName());
                executeCrawl(site, cat, maxPagesPerCategory, properties.getDefaultReqInterval(), fetchDetail);
            }

            log.info("========== 站点 {} 爬取完成 ==========", site.getName());
        }

        log.info("========== 所有站点爬取完成 ==========");
    }


    /**
     * 快速测试
     */
    public CrawlResult quickCrawl(CrawlerProperties.SiteConfig site,
                                  CrawlerProperties.CategoryConfig category, int maxPages) {
        return executeCrawl(site, category, maxPages, properties.getDefaultReqInterval(), false);
    }

    public JobStatus getJobStatus(String jobId) {
        return jobStatusMap.get(jobId);
    }

    /**
     * 根据 sourceUrl 和 siteName 获取详情
     * 自动匹配站点配置，根据引擎类型选择 HTML(Jsoup) 或 API 方式获取正文
     *
     * @param sourceUrl 原文链接（HTML站点）或详情API地址（API站点）
     * @param siteName  网站名称，用于匹配站点配置
     * @return 详情结果 Map（detail, bidAmount, bidWinner）
     */
    public Map<String, String> fetchDetail(String sourceUrl, String siteName) {
        Map<String, String> result = new HashMap<>();

        // 1. 根据 siteName 匹配站点配置
        CrawlerProperties.SiteConfig matchedSite = findSiteByName(siteName);

        if (matchedSite != null && "api".equalsIgnoreCase(matchedSite.getEngineType())) {
            // API 站点：sourceUrl 是详情API地址，直接 GET 获取 JSON
            result = fetchDetailByApi(sourceUrl, matchedSite);
        } else {
            // HTML 站点：用 Jsoup 解析详情页
            result = fetchDetailByHtml(sourceUrl, matchedSite);
        }

        // 3. 从正文提取所有结构化字段
        String detail = result.getOrDefault("detail", "");
        if (!detail.isBlank()) {
            CrawlItem temp = new CrawlItem();
            temp.setDetail(detail);
            extractStructuredFields(temp);
            if (temp.getBidAmount() != null) result.put("bidAmount", temp.getBidAmount());
            if (temp.getBidWinner() != null) result.put("bidWinner", temp.getBidWinner());
            if (temp.getBidDate() != null) result.put("bidDate", temp.getBidDate());
            if (temp.getProjectName() != null) result.put("projectName", temp.getProjectName());
            if (temp.getSectionName() != null) result.put("sectionName", temp.getSectionName());
            if (temp.getMaxPrice() != null) result.put("maxPrice", temp.getMaxPrice());
            if (temp.getOpenTime() != null) result.put("openTime", temp.getOpenTime());
            if (temp.getOpenPlace() != null) result.put("openPlace", temp.getOpenPlace());
            if (temp.getTenderer() != null) result.put("tenderer", temp.getTenderer());
            if (temp.getTenderContact() != null) result.put("tenderContact", temp.getTenderContact());
            if (temp.getTenderTel() != null) result.put("tenderTel", temp.getTenderTel());
        }

        return result;
    }

    private Map<String, String> fetchDetailByHtml(String sourceUrl, CrawlerProperties.SiteConfig site) {
        Map<String, String> result = new HashMap<>();
        CrawlItem item = new CrawlItem();
        item.setSourceUrl(sourceUrl);

        if (site != null) {
            // 使用默认处理器获取详情
            var defaultHandler = handlerFactory.getHandler("default", "default");
            defaultHandler.crawlDetail(item, site, 0);
        } else {
            // 无匹配站点配置，直接用 Jsoup 获取页面正文
            try {
                org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(sourceUrl)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(15000).maxBodySize(0).followRedirects(true).get();
                item.setDetail(doc.body().text());
            } catch (Exception e) {
                log.warn("详情页获取失败: {} - {}", sourceUrl, e.getMessage());
            }
        }

        result.put("detail", item.getDetail() != null ? item.getDetail() : "");
        return result;
    }

    private Map<String, String> fetchDetailByApi(String sourceUrl, CrawlerProperties.SiteConfig site) {
        Map<String, String> result = new HashMap<>();
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(sourceUrl))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response.body());
                // 尝试从 data 中提取正文
                String detail = extractDetailFromApiResponse(json);
                result.put("detail", detail != null ? detail : "");
            } else {
                log.warn("API详情获取失败: status={}, url={}", response.statusCode(), sourceUrl);
            }
        } catch (Exception e) {
            log.warn("API详情获取异常: {} - {}", sourceUrl, e.getMessage());
        }
        return result;
    }

    /**
     * 从 API 详情响应中提取正文内容
     * 尝试常见字段名：detail, content, noticeContent, body, text
     */
    private String extractDetailFromApiResponse(com.alibaba.fastjson2.JSONObject json) {
        if (json == null) return null;

        // 先尝试从 data 对象中取
        com.alibaba.fastjson2.JSONObject data = json.getJSONObject("data");
        com.alibaba.fastjson2.JSONObject target = data != null ? data : json;

        for (String key : List.of("detail", "content", "noticeContent", "body", "text", "htmlContent")) {
            String val = target.getString(key);
            if (val != null && !val.isBlank()) {
                // 去除HTML标签，保留纯文本
                return val.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            }
        }
        return null;
    }

    /**
     * 从正文中提取中标金额
     * 支持格式：中标金额：123.45万元、中标价格：123万、中标（成交）金额：xxx
     */
    private String extractBidAmount(String detail) {
        if (detail == null || detail.isBlank()) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:中标|成交|合同|投标)[^，。,\\.]{0,10}(?:金额|价格|总价|价款|报价)[：:\\s]*([\\d,\\.]+\\s*(?:万元|亿元|元)?)");
        java.util.regex.Matcher matcher = pattern.matcher(detail);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * 从正文中提取中标单位
     * 支持格式：中标单位：XXX公司、中标人：XXX、中标供应商：XXX
     */
    private String extractBidWinner(String detail) {
        if (detail == null || detail.isBlank()) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:中标|成交|中选)[^，。,\\.]{0,6}(?:单位|人|供应商|候选人|方)[：:\\s]*([^，。,;；\\s]{2,50}?)(?:[，。,;；\\s]|$)");
        java.util.regex.Matcher matcher = pattern.matcher(detail);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * API 站点的详情获取（用于爬取时自动获取）
     */
    private void fetchDetailByApi(CrawlItem item) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) return;
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(item.getSourceUrl()))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response.body());
                String detail = extractDetailFromApiResponse(json);
                if (detail != null) {
                    item.setDetail(detail);
                    item.setSummary(detail.length() > 500 ? detail.substring(0, 500) + "..." : detail);
                }
            }
        } catch (Exception e) {
            log.warn("API详情获取失败: {} - {}", item.getSourceUrl(), e.getMessage());
        }
    }

    /**
     * 从详情正文中提取结构化字段（招标/中标信息）
     */
    private void extractStructuredFields(CrawlItem item) {
        String detail = item.getDetail();
        if (detail == null || detail.isBlank()) return;

        // 招标类字段（只在字段为空时才提取，避免覆盖CrawlEngine中已提取的值）
        if (item.getProjectName() == null) {
            item.setProjectName(regexExtract(detail, "项目名称[：:：\\s]+(.+?)(?:[，。,\n]|$)"));
        }
        if (item.getSectionName() == null) {
            item.setSectionName(regexExtract(detail, "标段[（(包)）]?名称[：:：\\s]+(.+?)(?:[，。,\n]|$)"));
        }
        if (item.getMaxPrice() == null) {
            item.setMaxPrice(regexExtract(detail, "最高投标限价[：:：\\s]*([\\d,\\.]+\\s*(?:万元|亿元|元)?)"));
        }
        if (item.getOpenTime() == null) {
            item.setOpenTime(regexExtract(detail, "开标时间[：:：\\s]+(.+?)(?:[，。,\n]|$)"));
        }
        if (item.getOpenPlace() == null) {
            item.setOpenPlace(regexExtract(detail, "开标地点[：:：\\s]+(.+?)(?:[，。,\n]|$)"));
        }
        if (item.getTenderer() == null) {
            item.setTenderer(regexExtract(detail, "(?:招标人|招标单位|建设单位|代理机构|代理单位|采购人)[（(全称)）]*[：:：\\s]+([^，。,;；\\s\\d]{2,50}?)(?:[，。,;；\\s]|$)"));
        }
        if (item.getTenderContact() == null) {
            item.setTenderContact(regexExtract(detail, "(?:招标人)?联系人[：:：\\s]+([^，。,;；\\s]{2,20}?)(?:[，。,;；\\s]|$)"));
        }
        if (item.getTenderTel() == null) {
            item.setTenderTel(regexExtract(detail, "(?:联系)?电话[：:：\\s]*(\\d[\\d\\-()（）]+\\d)"));
        }

        // 中标类字段
        if (item.getBidWinner() == null) {
            item.setBidWinner(extractBidWinner(detail));
        }
        if (item.getBidAmount() == null) {
            item.setBidAmount(extractBidAmount(detail));
        }
        String bidDateRaw = regexExtract(detail, "中标日期[：:：\\s]*(\\d{4}[\\-/年]\\d{1,2}[\\-/月]\\d{1,2}日?)");
        if (bidDateRaw != null) item.setBidDate(normalizeDateTime(bidDateRaw));
    }

    /**
     * 通用正则��取（返回第一个捕获组，找不到返回 null）
     */
    private String regexExtract(String text, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String val = m.group(1).trim();
                return val.isBlank() ? null : val;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    static String normalizeDateTime(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim();
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
                "(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").matcher(s);
        if (!dm.find()) return s;
        String datePart = dm.group(1) + "-" + String.format("%02d", Integer.parseInt(dm.group(2)))
                + "-" + String.format("%02d", Integer.parseInt(dm.group(3)));
        String remaining = s.substring(dm.end()).trim();
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                "(\\d{1,2})[时:：](\\d{1,2})(?:[分:：](\\d{1,2})秒?)?").matcher(remaining);
        if (tm.find()) {
            return datePart + " " + String.format("%02d", Integer.parseInt(tm.group(1)))
                    + ":" + String.format("%02d", Integer.parseInt(tm.group(2)))
                    + ":" + (tm.group(3) != null ? String.format("%02d", Integer.parseInt(tm.group(3))) : "00");
        }
        return datePart;
    }

    /**
     * 根据 siteName 模糊匹配站点配置
     */
    private CrawlerProperties.SiteConfig findSiteByName(String siteName) {
        if (siteName == null || properties.getSites() == null) return null;
        for (var site : properties.getSites().values()) {
            if (site.getName().equals(siteName) || siteName.contains(site.getName()) || site.getName().contains(siteName)) {
                return site;
            }
        }
        return null;
    }

    // ---------- 查找站点和分类配置 ----------

    public CrawlerProperties.SiteConfig findSite(String siteKey) {
        return properties.getSites() == null ? null : properties.getSites().get(siteKey);
    }

    public record SiteCategoryPair(CrawlerProperties.SiteConfig site, CrawlerProperties.CategoryConfig category) {}

    public SiteCategoryPair findCategory(String categoryCode) {
        if (properties.getSites() == null) return null;
        for (var site : properties.getSites().values()) {
            if (site.getCategories() == null) continue;
            for (var cat : site.getCategories()) {
                if (cat.getCode().equals(categoryCode)) return new SiteCategoryPair(site, cat);
            }
        }
        return null;
    }

    // ---------- private ----------

    /**
     * 根据站点配置查找站点代码（sites Map 的 key）
     */
    private String findSiteCode(CrawlerProperties.SiteConfig site) {
        if (site == null || properties.getSites() == null) return "unknown";
        
        // 首先尝试对象引用匹配（快速路径）
        for (var entry : properties.getSites().entrySet()) {
            if (entry.getValue() == site) {
                return entry.getKey();
            }
        }
        
        // 备选：通过站点名称匹配
        for (var entry : properties.getSites().entrySet()) {
            if (entry.getValue() != null 
                && entry.getValue().getName() != null
                && site.getName() != null
                && entry.getValue().getName().equals(site.getName())) {
                return entry.getKey();
            }
        }
        
        // 备选2：根据站点名称特征推断
        if (site.getName() != null) {
            if (site.getName().contains("广东") && site.getName().contains("水利")) {
                return "gdygp-slgc";
            }
            if (site.getName().contains("广州")) {
                return "gzggzy-slsw";
            }
        }
        
        return "unknown";
    }

    private boolean existsByUrlHash(String urlHash) {
        return resultMapper.selectCount(
                Wrappers.<CrawlResultEntity>lambdaQuery()
                        .eq(CrawlResultEntity::getUrlHash, urlHash)) > 0;
    }

    private void saveResult(CrawlItem item) {
        CrawlResultEntity entity = new CrawlResultEntity();
        entity.setSiteName(item.getSiteName());
        entity.setTitle(item.getTitle());
        entity.setSummary(item.getSummary());
        entity.setSourceUrl(item.getSourceUrl());
        entity.setPublishTime(parseDate(item.getPublishTime()));
        entity.setCollectTime(new Date());
        entity.setReadStatus(0);
        entity.setUrlHash(item.getUrlHash());
        entity.setCategory(item.getCategory());
        entity.setCategoryCode(item.getCategoryCode());
        entity.setDetail(item.getDetail());
        entity.setHref(item.getWebUrl() != null && !item.getWebUrl().isBlank() ? item.getWebUrl() : item.getSourceUrl());
        entity.setProvince(item.getProvince());
        entity.setCity(item.getCity());
        entity.setSpiderStatus(item.getSpiderStatus());
        entity.setSynced(0);
        // v4 结构化字段
        entity.setProjectName(item.getProjectName());
        entity.setSectionName(item.getSectionName());
        entity.setMaxPrice(item.getMaxPrice());
        entity.setOpenTime(item.getOpenTime());
        entity.setOpenPlace(item.getOpenPlace());
        entity.setTenderer(item.getTenderer());
        entity.setTenderContact(item.getTenderContact());
        entity.setTenderTel(item.getTenderTel());
        entity.setBidWinner(item.getBidWinner());
        entity.setBidAmount(item.getBidAmount());
        entity.setBidDate(item.getBidDate());
        entity.setCreatorTime(new Date());
        resultMapper.insert(entity);
    }

    private void saveLog(String jobId, String siteName, String categoryName, String categoryCode,
                         int totalCount, int hitCount, long costMs, String errorMsg) {
        CrawlLogEntity logEntity = new CrawlLogEntity();
        logEntity.setId(jobId);
        logEntity.setSiteName(siteName);
        logEntity.setCategory(categoryName);
        logEntity.setTriggerType(0);
        logEntity.setExecStatus(errorMsg == null ? 1 : 2);
        logEntity.setStartTime(new Date(System.currentTimeMillis() - costMs));
        logEntity.setEndTime(new Date());
        logEntity.setTotalCount(totalCount);
        logEntity.setHitCount(hitCount);
        logEntity.setErrorMsg(errorMsg);
        logEntity.setSynced(0);
        logEntity.setCreatorTime(new Date());
        logMapper.insert(logEntity);
    }

    private Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            if (dateStr.length() == 10) {
                return new SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
            } else if (dateStr.length() >= 19) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(dateStr.substring(0, 19));
            }
        } catch (ParseException e) { log.debug("日期解析失败: {}", dateStr); }
        return null;
    }
}
