package com.jpwise.crawler.service.jianyu;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.jianyu.JianyuCompetitorCrawlRequest;
import com.jpwise.crawler.model.jianyu.JianyuCompetitorCrawlResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class JianyuCompetitorCrawlService {

    private static final String LIST_TABLE = "biz_competitor_enterprise";
    private static final String INFO_TABLE = "biz_competitor_info";
    private static final String DEFAULT_KEYWORD_SCOPE = "公告标题,公告正文,标的物,项目名称,采购单位,中标单位";

    private final CrawlerProperties properties;
    private final ObjectProvider<JdbcTemplate> externalJdbcTemplateProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public JianyuCompetitorCrawlService(
            CrawlerProperties properties,
            @Qualifier("externalBusinessJdbcTemplate") ObjectProvider<JdbcTemplate> externalJdbcTemplateProvider) {
        this.properties = properties;
        this.externalJdbcTemplateProvider = externalJdbcTemplateProvider;
    }

    public JianyuCompetitorCrawlResult crawl(JianyuCompetitorCrawlRequest request) {
        long start = System.currentTimeMillis();
        JianyuCompetitorCrawlResult result = new JianyuCompetitorCrawlResult();
        result.setJobId(UUID.randomUUID().toString().replace("-", ""));

        CrawlerProperties.Jianyu jianyu = properties.getJianyu();
        CrawlerProperties.ExternalBusiness externalBusiness = properties.getExternalBusiness();

        try {
            validateConfig(jianyu, externalBusiness);
            JdbcTemplate externalJdbcTemplate = getExternalJdbcTemplate();
            List<String> competitors = resolveCompetitors(externalJdbcTemplate, request);
            result.setCompetitorCount(competitors.size());
            int maxPages = positiveOrDefault(request == null ? null : request.getMaxPages(), jianyu.getDefaultMaxPages());
            int reqInterval = positiveOrDefault(request == null ? null : request.getReqInterval(), jianyu.getReqInterval());
            long publishStart = resolvePublishStart(request, jianyu);
            long publishEnd = resolvePublishEnd(request);

            log.info("剑鱼竞争对手爬取开始: jobId={}, 竞争对手{}个, 时间范围={}~{}, maxPages={}, reqInterval={}秒",
                    result.getJobId(), competitors.size(), parseUnixTime(String.valueOf(publishStart)),
                    parseUnixTime(String.valueOf(publishEnd)), maxPages, reqInterval);

            for (String competitorName : competitors) {
                crawlOneCompetitor(externalJdbcTemplate, competitorName, publishStart, publishEnd,
                        maxPages, reqInterval, result);
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.addError(e.getMessage(), jianyu.getMaxErrorMessages());
            log.error("剑鱼竞争对手爬取失败", e);
        } finally {
            result.setCostMs(System.currentTimeMillis() - start);
        }
        return result;
    }

    private void validateConfig(CrawlerProperties.Jianyu jianyu, CrawlerProperties.ExternalBusiness externalBusiness) {
        if (jianyu == null || !jianyu.isEnabled()) {
            throw new IllegalStateException("剑鱼竞争对手爬取未启用，请配置 crawler.jianyu.enabled=true");
        }
        if (externalBusiness == null || !externalBusiness.isEnabled()) {
            throw new IllegalStateException("外网平台业务库第二数据源未启用，请配置 crawler.external-business.enabled=true");
        }
        if (isBlank(jianyu.getAppid()) || isBlank(jianyu.getKey())) {
            throw new IllegalStateException("剑鱼 appid/key 未配置");
        }
    }

    private JdbcTemplate getExternalJdbcTemplate() {
        JdbcTemplate jdbcTemplate = externalJdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("外网平台业务库第二数据源未配置");
        }
        return jdbcTemplate;
    }

    private List<String> resolveCompetitors(JdbcTemplate jdbcTemplate, JianyuCompetitorCrawlRequest request) {
        if (request != null && !isBlank(request.getCompetitorName())) {
            return List.of(request.getCompetitorName().trim());
        }
        String sql = "SELECT DISTINCT enterprise_name FROM " + LIST_TABLE
                + " WHERE enterprise_name IS NOT NULL AND TRIM(enterprise_name) <> '' ORDER BY enterprise_name";
        List<String> names = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("enterprise_name"));
        return names.stream()
                .filter(name -> !isBlank(name))
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void crawlOneCompetitor(JdbcTemplate jdbcTemplate,
                                    String competitorName,
                                    long publishStart,
                                    long publishEnd,
                                    int maxPages,
                                    int reqInterval,
                                    JianyuCompetitorCrawlResult result) {
        Integer next = 0;
        int page = 0;
        while (next != null && page < maxPages) {
            page++;
            JSONObject response;
            Map<String, Object> listRequest = buildListRequest(competitorName, publishStart, publishEnd, next);
            try {
                response = postJson(properties.getJianyu().getApiUrl(), listRequest);
            } catch (Exception e) {
                result.addError("竞争对手[" + competitorName + "]列表请求失败: " + e.getMessage(),
                        properties.getJianyu().getMaxErrorMessages());
                break;
            }

            if (!"0".equals(response.getString("code"))) {
                result.addError("竞争对手[" + competitorName + "]列表接口返回异常: " + response
                                + "，请求参数: " + maskListRequest(listRequest),
                        properties.getJianyu().getMaxErrorMessages());
                break;
            }

            JSONArray data = response.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.info("剑鱼竞争对手列表无数据: competitor={}, page={}, next={}", competitorName, page, next);
                break;
            }

            log.info("剑鱼竞争对手列表返回: competitor={}, page={}, size={}, count={}, next={}",
                    competitorName, page, data.size(), response.getInteger("count"), response.getInteger("next"));

            for (int i = 0; i < data.size(); i++) {
                JSONObject listItem = data.getJSONObject(i);
                if (!isTargetSubtype(listItem.getString("subtype"))) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    continue;
                }
                result.setListCount(result.getListCount() + 1);
                saveListItem(jdbcTemplate, competitorName, listItem, reqInterval, result);
            }

            next = response.getInteger("next");
            if (next != null) {
                sleepSeconds(reqInterval);
            }
        }
    }

    private Map<String, Object> buildListRequest(String competitorName, long publishStart, long publishEnd, Integer next) {
        Map<String, Object> request = buildAuthRequest();
        request.put("keyword", competitorName);
        request.put("subType", properties.getJianyu().getSubType());
        request.put("publishtimeStart", publishStart);
        request.put("publishtimeEnd", publishEnd);
        request.put("next", next == null ? 0 : next);
        request.put("keywordScope", defaultIfBlank(properties.getJianyu().getKeywordScope(), DEFAULT_KEYWORD_SCOPE));
        putIfNotBlank(request, "industry", properties.getJianyu().getIndustry());
        return request;
    }

    private Map<String, Object> buildDetailRequest(String id) {
        Map<String, Object> request = buildAuthRequest();
        request.put("id", id);
        return request;
    }

    private Map<String, Object> maskListRequest(Map<String, Object> request) {
        Map<String, Object> masked = new LinkedHashMap<>(request);
        masked.remove("appid");
        masked.remove("token");
        masked.remove("timestamp");
        return masked;
    }

    private Map<String, Object> buildAuthRequest() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String appid = properties.getJianyu().getAppid();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("appid", appid);
        request.put("timestamp", timestamp);
        request.put("token", DigestUtil.md5Hex(appid + timestamp + properties.getJianyu().getKey()).toUpperCase());
        return request;
    }

    private void saveListItem(JdbcTemplate jdbcTemplate,
                              String competitorName,
                              JSONObject listItem,
                              int reqInterval,
                              JianyuCompetitorCrawlResult result) {
        String jianyuId = listItem.getString("id");
        String projectName = firstNotBlank(listItem.getString("projectname"), listItem.getString("title"));
        if (isBlank(projectName)) {
            result.setSkippedCount(result.getSkippedCount() + 1);
            return;
        }

        JSONObject detail = fetchDetail(jianyuId, reqInterval, result);
        String subtype = listItem.getString("subtype");
        String infoType = mapInfoType(subtype);
        LocalDateTime publishDate = parseUnixTime(firstNotBlank(listItem.getString("publishtime"), detail.getString("publishtime")));
        BigDecimal bidAmount = parseAmount(detail.getString("bidamount"));
        String announcementUrl = firstNotBlank(detail.getString("jybxhref"), detail.getString("href"));
        String bidWinner = firstNotBlank(detail.getString("s_winner"), listItem.getString("s_winner"));
        String tenderUnit = firstNotBlank(detail.getString("buyer"), listItem.getString("buyer"));
        String remark = buildRemark(jianyuId, listItem);

        try {
            if (existsByJianyuId(jdbcTemplate, jianyuId)
                    || existsByNaturalKey(jdbcTemplate, competitorName, projectName, publishDate, infoType)) {
                updateCompetitorInfo(jdbcTemplate, competitorName, projectName, publishDate, tenderUnit,
                        bidAmount, infoType, announcementUrl, remark, bidWinner);
                result.setUpdatedCount(result.getUpdatedCount() + 1);
                log.info("剑鱼竞争对手数据更新: competitor={}, type={}, project={}, publishDate={}, tenderUnit={}, bidWinner={}, bidAmount={}, jianyuId={}",
                        competitorName, infoType, projectName, publishDate, tenderUnit, bidWinner, bidAmount, jianyuId);
            } else {
                insertCompetitorInfo(jdbcTemplate, competitorName, projectName, publishDate, tenderUnit,
                        bidAmount, infoType, announcementUrl, remark, bidWinner);
                result.setInsertedCount(result.getInsertedCount() + 1);
                log.info("剑鱼竞争对手数据新增: competitor={}, type={}, project={}, publishDate={}, tenderUnit={}, bidWinner={}, bidAmount={}, jianyuId={}",
                        competitorName, infoType, projectName, publishDate, tenderUnit, bidWinner, bidAmount, jianyuId);
            }
        } catch (Exception e) {
            result.addError("竞争对手[" + competitorName + "]项目[" + projectName + "]入库失败: " + e.getMessage(),
                    properties.getJianyu().getMaxErrorMessages());
        }
    }

    private JSONObject fetchDetail(String jianyuId, int reqInterval, JianyuCompetitorCrawlResult result) {
        if (isBlank(jianyuId)) {
            return new JSONObject();
        }
        try {
            JSONObject response = postJson(properties.getJianyu().getInfoUrl(), buildDetailRequest(jianyuId));
            if ("0".equals(response.getString("code"))) {
                JSONArray data = response.getJSONArray("data");
                if (data != null && !data.isEmpty()) {
                    result.setDetailCount(result.getDetailCount() + 1);
                    sleepSeconds(reqInterval);
                    return data.getJSONObject(0);
                }
            } else {
                result.addError("剑鱼详情接口返回异常，id=" + jianyuId + ": " + response,
                        properties.getJianyu().getMaxErrorMessages());
            }
        } catch (Exception e) {
            result.addError("剑鱼详情请求失败，id=" + jianyuId + ": " + e.getMessage(),
                    properties.getJianyu().getMaxErrorMessages());
        }
        sleepSeconds(reqInterval);
        return new JSONObject();
    }

    private JSONObject postJson(String url, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return JSON.parseObject(response.body());
    }

    private boolean existsByJianyuId(JdbcTemplate jdbcTemplate, String jianyuId) {
        if (isBlank(jianyuId)) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + INFO_TABLE + " WHERE remark = ? OR remark LIKE ?",
                Long.class,
                "剑鱼ID:" + jianyuId,
                "剑鱼ID:" + jianyuId + "；%");
        return count != null && count > 0;
    }

    private boolean existsByNaturalKey(JdbcTemplate jdbcTemplate,
                                       String competitorName,
                                       String projectName,
                                       LocalDateTime publishDate,
                                       String infoType) {
        List<Object> params = new ArrayList<>();
        params.add(competitorName);
        params.add(projectName);
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM " + INFO_TABLE
                + " WHERE competitor_name = ? AND project_name = ?");
        appendNullableEquals(sql, params, "publish_date", publishDate);
        appendNullableEquals(sql, params, "info_type", infoType);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null && count > 0;
    }

    private void insertCompetitorInfo(JdbcTemplate jdbcTemplate,
                                      String competitorName,
                                      String projectName,
                                      LocalDateTime publishDate,
                                      String tenderUnit,
                                      BigDecimal bidAmount,
                                      String infoType,
                                      String announcementUrl,
                                      String remark,
                                      String bidWinner) {
        CrawlerProperties.ExternalBusiness external = properties.getExternalBusiness();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO " + INFO_TABLE
                        + " (id, competitor_name, project_name, publish_date, tender_unit, bid_amount, info_type,"
                        + " announcement_url, remark, created_time, updated_time, created_by, created_by_name,"
                        + " updated_by, updated_by_name, tenant_id, dept_id, bid_winner, industry, business_type)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString().replace("-", ""),
                trimToLength(competitorName, 200),
                trimToLength(projectName, 200),
                publishDate,
                trimToLength(tenderUnit, 200),
                bidAmount,
                trimToLength(infoType, 50),
                trimToLength(announcementUrl, 500),
                trimToLength(remark, 500),
                now,
                now,
                trimToLength(external.getCreatedBy(), 50),
                trimToLength(external.getCreatedByName(), 50),
                trimToLength(external.getUpdatedBy(), 50),
                trimToLength(external.getUpdatedByName(), 50),
                trimToLength(external.getTenantId(), 50),
                trimToLength(external.getDeptId(), 50),
                trimToLength(bidWinner, 200),
                trimToLength(external.getDefaultIndustry(), 50),
                trimToLength(external.getDefaultBusinessType(), 50));
    }

    private void updateCompetitorInfo(JdbcTemplate jdbcTemplate,
                                      String competitorName,
                                      String projectName,
                                      LocalDateTime publishDate,
                                      String tenderUnit,
                                      BigDecimal bidAmount,
                                      String infoType,
                                      String announcementUrl,
                                      String remark,
                                      String bidWinner) {
        CrawlerProperties.ExternalBusiness external = properties.getExternalBusiness();
        List<Object> params = new ArrayList<>();
        params.add(trimToLength(tenderUnit, 200));
        params.add(bidAmount);
        params.add(trimToLength(announcementUrl, 500));
        params.add(trimToLength(remark, 500));
        params.add(trimToLength(bidWinner, 200));
        params.add(LocalDateTime.now());
        params.add(trimToLength(external.getUpdatedBy(), 50));
        params.add(trimToLength(external.getUpdatedByName(), 50));
        params.add(trimToLength(competitorName, 200));
        params.add(trimToLength(projectName, 200));
        StringBuilder sql = new StringBuilder("UPDATE " + INFO_TABLE
                + " SET tender_unit = ?, bid_amount = ?, announcement_url = ?, remark = ?, bid_winner = ?,"
                + " updated_time = ?, updated_by = ?, updated_by_name = ?"
                + " WHERE competitor_name = ? AND project_name = ?");
        appendNullableEquals(sql, params, "publish_date", publishDate);
        appendNullableEquals(sql, params, "info_type", trimToLength(infoType, 50));
        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        String jianyuId = extractJianyuIdFromRemark(remark);
        if (updated == 0 && !isBlank(jianyuId)) {
            jdbcTemplate.update(
                    "UPDATE " + INFO_TABLE
                            + " SET tender_unit = ?, bid_amount = ?, announcement_url = ?, remark = ?, bid_winner = ?,"
                            + " updated_time = ?, updated_by = ?, updated_by_name = ?"
                            + " WHERE remark = ? OR remark LIKE ?",
                    trimToLength(tenderUnit, 200),
                    bidAmount,
                    trimToLength(announcementUrl, 500),
                    trimToLength(remark, 500),
                    trimToLength(bidWinner, 200),
                    LocalDateTime.now(),
                    trimToLength(external.getUpdatedBy(), 50),
                    trimToLength(external.getUpdatedByName(), 50),
                    "剑鱼ID:" + jianyuId,
                    "剑鱼ID:" + jianyuId + "；%");
        }
    }

    private String buildRemark(String jianyuId, JSONObject listItem) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(jianyuId)) {
            parts.add("剑鱼ID:" + jianyuId);
        }
        appendRemark(parts, "地区", listItem.getString("area"));
        appendRemark(parts, "城市", listItem.getString("city"));
        appendRemark(parts, "一级分类", listItem.getString("toptype"));
        appendRemark(parts, "二级分类", listItem.getString("subtype"));
        return trimToLength(String.join("；", parts), 500);
    }

    private void appendRemark(List<String> parts, String label, String value) {
        if (!isBlank(value)) {
            parts.add(label + ":" + value.trim());
        }
    }

    private void appendNullableEquals(StringBuilder sql, List<Object> params, String field, Object value) {
        if (value == null) {
            sql.append(" AND ").append(field).append(" IS NULL");
            return;
        }
        sql.append(" AND ").append(field).append(" = ?");
        params.add(value);
    }

    private boolean isTargetSubtype(String subtype) {
        String configured = properties.getJianyu().getSubType();
        if (isBlank(configured)) {
            return Objects.equals("中标", subtype) || Objects.equals("招标", subtype);
        }
        for (String item : configured.split(",")) {
            if (item.trim().equals(subtype)) {
                return true;
            }
        }
        return false;
    }

    private String mapInfoType(String subtype) {
        if ("中标".equals(subtype)) {
            return "中标方";
        }
        if ("招标".equals(subtype)) {
            return "投标方";
        }
        return subtype;
    }

    private BigDecimal parseAmount(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String text = raw.trim().replace(",", "");
        BigDecimal multiplier = BigDecimal.ONE;
        if (text.contains("亿元") || text.contains("亿")) {
            multiplier = new BigDecimal("100000000");
        } else if (text.contains("万元") || text.contains("万")) {
            multiplier = new BigDecimal("10000");
        }
        String number = text.replaceAll("[^0-9.\\-]", "");
        if (isBlank(number) || "-".equals(number)) {
            return null;
        }
        try {
            return new BigDecimal(number).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseUnixTime(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value > 9999999999L) {
                value = value / 1000;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private long resolvePublishStart(JianyuCompetitorCrawlRequest request, CrawlerProperties.Jianyu jianyu) {
        if (request != null && request.getPublishtimeStart() != null) {
            return request.getPublishtimeStart();
        }
        int days = positiveOrDefault(request == null ? null : request.getDays(), jianyu.getDefaultDays());
        return LocalDateTime.now().minusDays(days).atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private long resolvePublishEnd(JianyuCompetitorCrawlRequest request) {
        if (request != null && request.getPublishtimeEnd() != null) {
            return request.getPublishtimeEnd();
        }
        return Instant.now().getEpochSecond();
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (!isBlank(value)) {
            map.put(key, value.trim());
        }
    }

    private String firstNotBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : (!isBlank(second) ? second.trim() : null);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.indexOf('?') >= 0 ? defaultValue : trimmed;
    }

    private String trimToLength(String value, int length) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String extractJianyuIdFromRemark(String remark) {
        if (isBlank(remark) || !remark.startsWith("剑鱼ID:")) {
            return "";
        }
        int end = remark.indexOf('；');
        return end > 0 ? remark.substring("剑鱼ID:".length(), end) : remark.substring("剑鱼ID:".length());
    }

    private void sleepSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
