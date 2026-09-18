package com.jpwise.crawler.engine.handler.site.gdgzy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 广东省公共资源交易中心 - 招标公告处理器
 * 站点代码: gdgzy 或 gdygp-slgc
 * 分类代码: zbgg 或 逗号分隔的多个代码如 "3C11,3C12,3C13,3C14"
 *
 * 特点：
 * - 列表页使用 API 引擎（广东省是API型站点）
 * - 详情页使用 HTML (Jsoup) 方式获取，保留 HTML 结构以便扫描块级元素提取招标人
 */
@Slf4j
@Component
public class GdgzyZbggHandler implements CrawlHandler {

    // 支持多个站点代码
    private static final String[] SITE_CODES = {"gdgzy", "gdygp-slgc"};
    // 支持标准分类代码或逗号分隔的多个代码
    private static final String[] CATEGORY_CODES = {"zbgg", "3C11", "3C12", "3C13", "3C14"};

    @Autowired
    private com.jpwise.crawler.engine.ApiCrawlEngine apiCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        // 检查站点代码
        boolean siteMatch = false;
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) {
                siteMatch = true;
                break;
            }
        }
        if (!siteMatch) return false;

        // 检查分类代码（支持逗号分隔的多个值）
        if (categoryCode == null) return false;

        // 如果是逗号分隔的，检查是否包含任何一个支持的分类
        String[] categories = categoryCode.split(",");
        for (String cat : categories) {
            cat = cat.trim();
            for (String supportedCat : CATEGORY_CODES) {
                if (supportedCat.equals(cat)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String getHandlerName() {
        return "广东省交易中心-招标公告处理器";
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getName(), maxPages);

        // 使用 API 引擎爬取列表（广东省使用 API 引擎）
        List<CrawlItem> items = apiCrawlEngine.crawlList(site, category, maxPages, reqInterval);

        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String BASE_API = "https://ygp.gdzwfw.gov.cn/ggzy-portal/center/apis";

    // nodeId 缓存：key = citySiteCode|tradingType|bizCode|classify → nodeId（"NONE"表示已请求但无结果）
    private static final String NODE_ID_NONE = "NONE";
    private final Map<String, String> nodeIdCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());

        crawlDetailByNewApi(item, reqInterval);

        log.info("[{}] 详情获取完成: 项目={}, 标段={}, 限价={}, 招标人={}, 联系人={}, 电话={}",
            getHandlerName(), item.getProjectName(), item.getSectionName(),
            item.getMaxPrice(), item.getTenderer(), item.getTenderContact(), item.getTenderTel());
    }

    /**
     * 广东省新版详情 API（2026年验证有效）
     *
     * 流程：
     *   1. 从 sourceUrl 中解析出 siteCode, tradingProcess(bizCode), noticeId, projectCode, edition
     *   2. 调用 singleNode API 获取 nodeId（按 siteCode+bizCode 缓存）
     *   3. 调用 new/detail API 获取结构化详情数据
     *   4. 从 multiKeyValueTableList 直接读取字段（比正则精准）
     *   5. 从 richtext 提取 HTML 正文
     */
    private void crawlDetailByNewApi(CrawlItem item, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) return;

        try {
            if (reqInterval > 0) Thread.sleep(reqInterval * 1000L);

            // 从 sourceUrl 解析参数
            java.net.URL url = java.net.URI.create(item.getSourceUrl()).toURL();
            Map<String, String> params = parseQueryParams(url.getQuery());

            String siteCode = params.getOrDefault("siteCode", "");
            // singleNode API 需要市级 siteCode（后两位为00），如 441301 → 441300
            String citySiteCode = siteCode.length() >= 4 ? siteCode.substring(0, 4) + "00" : siteCode;
            String bizCode = params.getOrDefault("tradingProcess", "");
            String noticeId = params.getOrDefault("noticeId", "");
            String projectCode = params.getOrDefault("projectCode", "");
            String edition = "v3"; // 从URL路径中提取
            java.util.regex.Matcher edMatcher = java.util.regex.Pattern.compile("/trading-notice/(v\\d+)/").matcher(item.getSourceUrl());
            if (edMatcher.find()) edition = edMatcher.group(1);

            // 1. 获取 nodeId（按市级siteCode+bizCode缓存）
            String cacheKey = citySiteCode + "|A|" + bizCode + "|A07";
            String nodeId = nodeIdCache.computeIfAbsent(cacheKey, k -> {
                try {
                    String nodeUrl = BASE_API + "/trading-notice/new/singleNode?siteCode=" + citySiteCode
                            + "&tradingType=A&bizCode=" + bizCode + "&classify=A07";
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(nodeUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("Accept", "*/*")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .GET().build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    String body = resp.body().trim();
                    log.debug("[{}] singleNode(siteCode={}) 响应: {}", getHandlerName(), citySiteCode, body);
                    if (body.matches("\\d+")) return body;
                    JSONObject j = JSON.parseObject(body);
                    if (j != null && j.get("data") != null) return j.getString("data");
                    return null;
                } catch (Exception e) {
                    log.warn("[{}] 获取nodeId失败(siteCode={}): {}", getHandlerName(), citySiteCode, e.getMessage());
                    return NODE_ID_NONE;
                }
            });

            if (nodeId == null || nodeId.isBlank() || "null".equals(nodeId) || NODE_ID_NONE.equals(nodeId)) {
                log.warn("[{}] 无法获取nodeId, 跳过详情: siteCode={}, citySiteCode={}, bizCode={}", getHandlerName(), siteCode, citySiteCode, bizCode);
                return;
            }

            // 2. 调用 new/detail API（siteCode 用市级）
            String detailUrl = BASE_API + "/trading-notice/new/detail?version=" + edition
                    + "&tradingType=A&noticeId=" + noticeId
                    + "&bizCode=" + bizCode
                    + "&projectCode=" + projectCode
                    + "&siteCode=" + citySiteCode
                    + "&nodeId=" + nodeId;

            log.debug("[{}] 请求新版详情API: {}", getHandlerName(), detailUrl);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(detailUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[{}] 详情API返回非200: {}", getHandlerName(), response.statusCode());
                return;
            }

            JSONObject json = JSON.parseObject(response.body());
            if (json == null || json.getIntValue("errcode") != 0) {
                log.warn("[{}] 详情API错误: {}", getHandlerName(),
                    json != null ? json.getString("errmsg") : "null response");
                return;
            }

            JSONObject data = json.getJSONObject("data");
            if (data == null) return;

            // 3. 从结构化数据中提取字段
            extractFromStructuredData(item, data);

            // 4. 从 richtext 提取 HTML 正文（用于 summary 和 detail）
            extractRichtextContent(item, data);

            log.debug("[{}] 新版API详情获取成功", getHandlerName());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[{}] 详情获取异常: {} - {}", getHandlerName(), item.getTitle(), e.getMessage());
        }
    }

    /**
     * 从新版 API 的结构化数据中直接提取字段
     * data.tradingNoticeColumnModelList[].multiKeyValueTableList[][] 包含 {code, key, value}
     */
    private void extractFromStructuredData(CrawlItem item, JSONObject data) {
        com.alibaba.fastjson2.JSONArray columns = data.getJSONArray("tradingNoticeColumnModelList");
        if (columns == null) return;

        for (int i = 0; i < columns.size(); i++) {
            JSONObject col = columns.getJSONObject(i);
            if (col == null) continue;

            com.alibaba.fastjson2.JSONArray multiKvTable = col.getJSONArray("multiKeyValueTableList");
            if (multiKvTable == null) continue;

            for (int j = 0; j < multiKvTable.size(); j++) {
                com.alibaba.fastjson2.JSONArray kvList = multiKvTable.getJSONArray(j);
                if (kvList == null) continue;

                for (int k = 0; k < kvList.size(); k++) {
                    JSONObject kv = kvList.getJSONObject(k);
                    if (kv == null) continue;

                    String code = kv.getString("code");
                    String value = kv.getString("value");
                    if (code == null || value == null || value.isBlank()) continue;

                    switch (code) {
                        case "TENDER_PROJECT_NAME" -> {
                            if (item.getProjectName() == null) item.setProjectName(value);
                        }
                        case "BID_SECTION_NAME" -> {
                            if (item.getSectionName() == null) item.setSectionName(value);
                        }
                        case "TENDER_PROJECT_CLASSIFY_NAME" -> {
                            // 项目分类，忽略
                        }
                        case "MAX_BID_LIMIT_PRICE", "BUDGET_PRICE" -> {
                            if (item.getMaxPrice() == null) item.setMaxPrice(value);
                        }
                        case "BID_OPEN_TIME", "SUBMIT_DEADLINE" -> {
                            if (item.getOpenTime() == null) item.setOpenTime(normalizeDateTime(value));
                        }
                        case "BID_OPEN_PLACE" -> {
                            if (item.getOpenPlace() == null) item.setOpenPlace(value);
                        }
                        case "TENDERER_NAME", "PURCHASER_NAME" -> {
                            if (item.getTenderer() == null) item.setTenderer(value);
                        }
                        case "TENDERER_CONTACT", "PURCHASER_CONTACT" -> {
                            if (item.getTenderContact() == null) item.setTenderContact(value);
                        }
                        case "TENDERER_PHONE", "PURCHASER_PHONE" -> {
                            if (item.getTenderTel() == null) item.setTenderTel(value);
                        }
                        case "WIN_BIDDER_NAME" -> {
                            if (item.getBidWinner() == null) item.setBidWinner(value);
                        }
                        case "WIN_BID_PRICE", "BID_AMOUNT" -> {
                            if (item.getBidAmount() == null) item.setBidAmount(value);
                        }
                        default -> {
                            // 用 key 名称做 fallback 匹配
                            String key = kv.getString("key");
                            if (key != null) matchByKeyName(item, key, value);
                        }
                    }
                }
            }
        }
    }

    /**
     * 按中文 key 名称匹配字段（code 不在已知列表时的 fallback）
     */
    private void matchByKeyName(CrawlItem item, String key, String value) {
        // 过滤无效值
        if ("Invalid Date".equalsIgnoreCase(value)) return;

        if (key.contains("招标项目名称") && item.getProjectName() == null) {
            item.setProjectName(value);
        } else if (key.contains("标段") && key.contains("名称") && item.getSectionName() == null) {
            item.setSectionName(value);
        } else if ((key.contains("最高投标限价") || key.contains("最高限价")) && item.getMaxPrice() == null) {
            item.setMaxPrice(value);
        } else if ((key.contains("开标开始时间") || key.contains("开标时间") || key.contains("投标截止时间"))
                && item.getOpenTime() == null && value.matches(".*\\d{4}[-/年].*")) {
            item.setOpenTime(normalizeDateTime(value));
        } else if ((key.contains("开标地点") || key.contains("开评标地址")) && item.getOpenPlace() == null
                && !value.contains("详见")) {
            item.setOpenPlace(value);
        } else if (key.startsWith("招标人") && !key.contains("联系人") && !key.contains("代理") && item.getTenderer() == null) {
            item.setTenderer(value);
        } else if ((key.equals("招标人联系人") || key.equals("联系人")) && item.getTenderContact() == null) {
            item.setTenderContact(value);
        } else if (key.equals("联系电话") && item.getTenderTel() == null) {
            item.setTenderTel(value);
        } else if (key.contains("中标人") && item.getBidWinner() == null) {
            item.setBidWinner(value);
        } else if (key.contains("中标价") && item.getBidAmount() == null) {
            item.setBidAmount(value);
        }
    }

    /**
     * 从 richtext 字段提取 HTML 正文
     */
    private void extractRichtextContent(CrawlItem item, JSONObject data) {
        com.alibaba.fastjson2.JSONArray columns = data.getJSONArray("tradingNoticeColumnModelList");
        if (columns == null) return;

        StringBuilder htmlBuilder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            JSONObject col = columns.getJSONObject(i);
            if (col == null) continue;
            String richtext = col.getString("richtext");
            if (richtext != null && !richtext.isBlank()) {
                htmlBuilder.append(richtext);
            }
        }

        if (!htmlBuilder.isEmpty()) {
            String html = htmlBuilder.toString();
            Document doc = Jsoup.parse(html);
            String textContent = doc.text();
            item.setDetail(textContent);
            item.setSummary(textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent);

            // 1. 从 HTML 表格提取字段
            extractFromTable(item, doc.body());

            // 2. 纯文本 fallback（处理没有表格的招标公告，如清远等纯段落格式）
            extractFromPlainText(item, textContent);
        }
    }

    /**
     * 从纯文本中提取字段（用于没有表格的招标公告，如清远等纯段落格式）
     * 只在对应字段尚未提取到时才尝试
     */
    private void extractFromPlainText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 招标人/招标单位
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "(?:招标人|招标单位)[：:：\\s]+([^，。,;；\\s\\n]{2,50}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        // 联系人
        if (item.getTenderContact() == null) {
            String val = regexFirst(text, "(?:招标人)?联系人[：:：\\s]+([^，。,;；\\s\\n]{2,20}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setTenderContact(val);
        }
        // 联系电话
        if (item.getTenderTel() == null) {
            String val = regexFirst(text, "联系电话[：:：\\s]*([\\d\\-()（）]{7,20})");
            if (val != null) item.setTenderTel(val);
        }
        // 开标时间
        if (item.getOpenTime() == null) {
            String val = regexFirst(text, "(?:开标时间|开标开始时间|投标截止时间)[：:：\\s]*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[时：:]\\d{1,2}(?:[分：:]\\d{1,2}秒?)?)?)");
            if (val != null) item.setOpenTime(normalizeDateTime(val));
        }
        // 开标地点
        if (item.getOpenPlace() == null) {
            String val = regexFirst(text, "(?:开标地点|开标场所|开评标地址)[：:：\\s]+(.+?)(?=[，。\\n]|$)");
            if (val != null && !val.contains("详见") && val.length() >= 4) item.setOpenPlace(val);
        }
        // 最高投标限价
        if (item.getMaxPrice() == null) {
            String val = regexFirst(text, "(?:最高投标限价|最高限价|招标控制价)[（(]?万元[）)]?[：:：\\s]*([\\d,\\.]+)");
            if (val == null) val = regexFirst(text, "(?:最高投标限价|最高限价|招标控制价)[：:：\\s]*([\\d,\\.]+)\\s*万?元?");
            if (val != null) item.setMaxPrice(val.replace(",", ""));
        }
        // 项目名称
        if (item.getProjectName() == null) {
            String val = regexFirst(text, "(?:招标项目名称|项目名称|工程名称)[：:：\\s]+(.+?)(?=[，。\\n项目编号]|$)");
            if (val != null && val.length() >= 4) item.setProjectName(val);
        }
    }

    private String regexFirst(String text, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String val = m.group(1).trim();
                return val.isBlank() ? null : val;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /**
     * 将各种中文日期时间格式统一为 yyyy-MM-dd HH:mm:ss
     * 支持格式：
     *   2026年03月25日 09:00:00 → 2026-03-25 09:00:00
     *   2026年4月10日9时00分    → 2026-04-10 09:00:00
     *   2026年04月13日 09:30    → 2026-04-13 09:30:00
     *   2026-04-10 09:00       → 2026-04-10 09:00:00
     *   2026-04-10              → 2026-04-10
     */
    static String normalizeDateTime(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim();

        // 提取日期部分：2026年03月25日 或 2026年4月10日 或 2026-04-10 或 2026/04/10
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
                "(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").matcher(s);
        if (!dm.find()) return s; // 无法识别，原样返回

        String year = dm.group(1);
        String month = String.format("%02d", Integer.parseInt(dm.group(2)));
        String day = String.format("%02d", Integer.parseInt(dm.group(3)));
        String datePart = year + "-" + month + "-" + day;

        // 提取时间部分：09:00:00 或 09:30 或 9时00分 或 09时30分00秒
        String remaining = s.substring(dm.end()).trim();
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile(
                "(\\d{1,2})[时:：](\\d{1,2})(?:[分:：](\\d{1,2})秒?)?").matcher(remaining);
        if (tm.find()) {
            String hour = String.format("%02d", Integer.parseInt(tm.group(1)));
            String min = String.format("%02d", Integer.parseInt(tm.group(2)));
            String sec = tm.group(3) != null ? String.format("%02d", Integer.parseInt(tm.group(3))) : "00";
            return datePart + " " + hour + ":" + min + ":" + sec;
        }

        return datePart;
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String val = eq < pair.length() - 1 ? java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8) : "";
                params.put(key, val);
            }
        }
        return params;
    }

    // ==================== 以下为旧方法（已被新版 API 替代，保留 extractFromTable 用于 richtext 解析） ====================

    @SuppressWarnings("unused")
    private String extractHtmlFromJson_DEPRECATED(JSONObject json, String rawBody) {
        // 已知的 HTML 内容字段名
        List<String> contentKeys = List.of("noticeContent", "content", "detail", "htmlContent", "body", "text", "noticeBody");

        // 第1层：直接在顶层找
        for (String key : contentKeys) {
            String val = json.getString(key);
            if (val != null && val.length() > 100 && (val.contains("<") || val.contains("&lt;"))) {
                log.debug("[{}] 从顶层字段 '{}' 找到HTML内容", getHandlerName(), key);
                return val;
            }
        }

        // 第2层：在 data 对象里找（data 可能是 JSONObject）
        Object dataObj = json.get("data");
        if (dataObj instanceof JSONObject dataJson) {
            log.debug("[{}] data 是 JSONObject，字段: {}", getHandlerName(), dataJson.keySet());
            for (String key : contentKeys) {
                String val = dataJson.getString(key);
                if (val != null && val.length() > 100 && (val.contains("<") || val.contains("&lt;"))) {
                    log.debug("[{}] 从 data.{} 找到HTML内容", getHandlerName(), key);
                    return val;
                }
            }

            // 第3层：data 下的嵌套对象
            for (String nestedKey : dataJson.keySet()) {
                Object nested = dataJson.get(nestedKey);
                if (nested instanceof JSONObject nestedJson) {
                    for (String key : contentKeys) {
                        String val = nestedJson.getString(key);
                        if (val != null && val.length() > 100 && (val.contains("<") || val.contains("&lt;"))) {
                            log.debug("[{}] 从 data.{}.{} 找到HTML内容", getHandlerName(), nestedKey, key);
                            return val;
                        }
                    }
                }
            }

            // data 中没找到 HTML 字段，但 data 可能有很长的字符串值就是 HTML
            for (String key : dataJson.keySet()) {
                Object val = dataJson.get(key);
                if (val instanceof String str && str.length() > 200 && str.contains("<")) {
                    log.debug("[{}] 从 data.{} 找到疑似HTML内容(长度{})", getHandlerName(), key, str.length());
                    return str;
                }
            }
        } else if (dataObj != null) {
            log.debug("[{}] data 字段类型: {}, 值前100字符: {}", getHandlerName(),
                dataObj.getClass().getSimpleName(),
                dataObj.toString().substring(0, Math.min(100, dataObj.toString().length())));
        }

        // 最后兜底：在整个 JSON 原始文本中找 HTML 片段
        // 如果原始响应中包含很长的 HTML，用正则提取
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\"(?:noticeContent|content|detail|htmlContent|body)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(rawBody);
        if (m.find()) {
            String val = m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\/", "/");
            if (val.length() > 100) {
                log.debug("[{}] 从原始JSON用正则找到HTML内容(长度{})", getHandlerName(), val.length());
                return val;
            }
        }

        return null;
    }

    /**
     * 广东省招标公告详情页特定提取逻辑
     * 从正文中提取所有招标相关字段
     * 策略：表格提取（优先） + 标签匹配 + 落款匹配
     */
    private void extractGdgzyZbggDetail(CrawlItem item) {
        if (item.getDetail() == null || item.getDetail().isBlank()) {
            return;
        }

        // detail 存的是原始 HTML，用 Jsoup 解析
        Document doc = Jsoup.parse(item.getDetail());
        Element contentMain = doc.body();

        // ========== 优先从表格提取（最准确） ==========
        extractFromTable(item, contentMain);

        String fullText = contentMain.text();

        // 将 detail 替换为纯文本（供后续 CrawlExecutorService 正则 fallback 使用）
        item.setDetail(fullText);

        // ========== 1. 提取项目名称 ==========
        if (item.getProjectName() == null || item.getProjectName().isBlank()) {
            String[] projectPatterns = {
                "招标项目名称[：:\\s]*(.+?)(?=\\n|项目编号|标段|$)",
                "项目名称[：:\\s]*(.+?)(?=\\n|项目编号|标段|$)",
                "工程名称[：:\\s]*(.+?)(?=\\n|项目编号|标段|$)"
            };
            for (String pattern : projectPatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 4) {
                        item.setProjectName(val);
                        log.debug("[{}] 提取项目名称: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 2. 提取标段（包）名称 ==========
        if (item.getSectionName() == null || item.getSectionName().isBlank()) {
            String[] sectionPatterns = {
                "标段[\\(（]包[\\)）]?名称[：:\\s]*(.+?)(?=\\n|标段编号|招标范围|$)",
                "标段名称[：:\\s]*(.+?)(?=\\n|标段编号|$)",
                "包名称[：:\\s]*(.+?)(?=\\n|包号|$)",
                "本次招标范围[：:\\s]*(.+?)(?=\\n|建设地点|$)"
            };
            for (String pattern : sectionPatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 4) {
                        item.setSectionName(val);
                        log.debug("[{}] 提取标段名称: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 3. 提取最高投标限价（万元） ==========
        if (item.getMaxPrice() == null || item.getMaxPrice().isBlank()) {
            String[] pricePatterns = {
                "最高投标限价[（(]万元[）)]?[：:\\s]*([\\d,\\.]+)",
                "最高限价[（(]万元[）)]?[：:\\s]*([\\d,\\.]+)",
                "最高投标限价[：:\\s]*([\\d,\\.]+)[\\s]*万元",
                "投标限价[：:\\s]*([\\d,\\.]+)[\\s]*万元",
                "招标控制价[：:\\s]*([\\d,\\.]+)[\\s]*万元"
            };
            for (String pattern : pricePatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim().replace(",", "");
                    if (!val.isBlank()) {
                        item.setMaxPrice(val);
                        log.debug("[{}] 提取最高投标限价: {}万元", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 4. 提取开标时间 ==========
        if (item.getOpenTime() == null || item.getOpenTime().isBlank()) {
            String[] timePatterns = {
                "开标时间[：:\\s]*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[：:]\\d{2})?)",
                "投标截止时间[：:\\s]*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[：:]\\d{2})?)",
                "递交投标文件截止时间[：:\\s]*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[：:]\\d{2})?)"
            };
            for (String pattern : timePatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank()) {
                        item.setOpenTime(val);
                        log.debug("[{}] 提取开标时间: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 5. 提取开标地点 ==========
        if (item.getOpenPlace() == null || item.getOpenPlace().isBlank()) {
            String[] placePatterns = {
                "开标地点[：:\\s]*(.+?)(?=\\n|评标办法|$)",
                "开标场所[：:\\s]*(.+?)(?=\\n|$)",
                "递交投标文件地点[：:\\s]*(.+?)(?=\\n|$)"
            };
            for (String pattern : placePatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 4) {
                        item.setOpenPlace(val);
                        log.debug("[{}] 提取开标地点: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 6. 提取招标人 ==========
        extractTenderer(item, contentMain, fullText);

        // ========== 7. 提取招标人联系人 ==========
        if (item.getTenderContact() == null || item.getTenderContact().isBlank()) {
            String[] contactPatterns = {
                "招标人[\\s\\S]*?联系人[：:\\s]*([^\\n，。,；;]{2,20}?)(?=\\n|联系电话|电话|$)",
                "联系人[：:\\s]*([^\\n，。,；;]{2,20}?)(?=\\n|联系电话|电话|$)",
                "招标代理.*?联系人[：:\\s]*([^\\n，。,；;]{2,20}?)(?=\\n|$)"
            };
            for (String pattern : contactPatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 2 && !val.contains("电话")) {
                        item.setTenderContact(val);
                        log.debug("[{}] 提取联系人: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        // ========== 8. 提取联系电话 ==========
        if (item.getTenderTel() == null || item.getTenderTel().isBlank()) {
            String[] telPatterns = {
                "联系电话[：:\\s]*([\\d\\-()（）]{7,20})",
                "联系[电话]*方式[：:\\s]*([\\d\\-()（）]{7,20})",
                "电话[：:\\s]*([\\d\\-()（）]{7,20})",
                "招标人.*?电话[：:\\s]*([\\d\\-()（）]{7,20})",
                "代理机构.*?电话[：:\\s]*([\\d\\-()（）]{7,20})"
            };
            for (String pattern : telPatterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 7) {
                        item.setTenderTel(val);
                        log.debug("[{}] 提取联系电话: {}", getHandlerName(), val);
                        break;
                    }
                }
            }
        }

        log.info("[{}] 字段提取完成: 项目={}, 标段={}, 限价={}, 开标时间={}, 地点={}, 招标人={}, 联系人={}, 电话={}",
            getHandlerName(),
            item.getProjectName(),
            item.getSectionName(),
            item.getMaxPrice(),
            item.getOpenTime(),
            item.getOpenPlace(),
            item.getTenderer(),
            item.getTenderContact(),
            item.getTenderTel());
    }

    /**
     * 提取招标人（标签匹配 + 落款匹配）
     */
    private void extractTenderer(CrawlItem item, Element contentMain, String fullText) {
        // ---- 策略1：标签匹配 ----
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
            String[] labelPatterns = {
                "招标人[名称]*[：:\\s]+([^\\n，。,;；\\s（(]{2,50}?)(?=[\\n，。,;；\\s（(]|$)",
                "招标单位[（(全称)）]*[：:\\s]+([^\\n，。,;；\\s]{2,50}?)(?=[\\n，。,;；\\s]|$)",
                "建设单位[：:\\s]+([^\\n，。,;；\\s]{2,50}?)(?=[\\n，。,;；\\s]|$)",
                "采购人[名称]*[：:\\s]+([^\\n，。,;；\\s]{2,50}?)(?=[\\n，。,;；\\s]|$)"
            };
            for (String pattern : labelPatterns) {
                try {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                    if (m.find()) {
                        String val = m.group(1).trim();
                        if (!val.isBlank() && val.length() >= 4 && !val.contains("联系人")) {
                            item.setTenderer(val);
                            log.debug("[{}] 从标签提取招标人: {}", getHandlerName(), val);
                            break;
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }

        // ---- 策略2：落款匹配（从末尾向前找单位名称） ----
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
            Elements blocks = contentMain.select("p, div, span, td, tr");
            String orgSuffixes = "(?:中心|公司|局|处|院|所|厅|委|办|站|队|部|会|集团|总队|支队|大学|学院|学校|研究所|设计院|监理部|事务所|研究院|开发部|管理局|有限公司|分公司|子公司|营业部|项目部|工程部|指挥部|监督站)";

            for (int i = blocks.size() - 1; i >= Math.max(0, blocks.size() - 30); i--) {
                String text = blocks.get(i).text().trim();

                if (text.isBlank() || text.length() < 4 || text.length() > 100) continue;
                if (text.contains("特此公告") || text.contains("特此通知") || text.contains("日期")) continue;
                if (text.contains("招标代理") || text.contains("联系人") || text.contains("电话")) continue;

                if (text.matches(".*" + orgSuffixes + "$")) {
                    item.setTenderer(text);
                    log.debug("[{}] 从落款提取招标人: {}", getHandlerName(), text);
                    break;
                }
            }
        }
    }

    /**
     * 从 HTML 表格中提取字段（针对表格格式的招标公告）
     */
    private void extractFromTable(CrawlItem item, Element contentMain) {
        Elements tables = contentMain.select("table");

        for (Element table : tables) {
            for (Element row : table.select("tr")) {
                Elements cells = row.select("td, th");
                if (cells.size() < 2) continue;

                // 处理所有相邻的标签-值对（2列或4列格式）
                for (int c = 0; c + 1 < cells.size(); c += 2) {
                    String label = cells.get(c).text().trim();
                    String value = cells.get(c + 1).text().trim();
                    if (value.isBlank() || "Invalid Date".equalsIgnoreCase(value)) continue;

                    matchTableField(item, label, value);
                }
            }
        }
    }

    private void matchTableField(CrawlItem item, String label, String value) {
        // 项目名称
        if ((label.contains("招标项目名称") || label.contains("工程名称")) && item.getProjectName() == null) {
            item.setProjectName(value);
        }
        // 标段名称
        else if (label.contains("标段") && label.contains("名称") && item.getSectionName() == null) {
            item.setSectionName(value);
        }
        // 最高限价
        else if (label.contains("限价") && item.getMaxPrice() == null) {
            String price = value.replaceAll("[^\\d\\.]", "");
            if (!price.isBlank()) item.setMaxPrice(price);
        }
        // 开标时间：只接受包含日期的值，统一格式为 yyyy-MM-dd HH:mm:ss
        else if ((label.equals("开标开始时间") || label.equals("开标时间")
                || label.contains("递交投标文件截止时间") || label.contains("投标截���时间"))
                && item.getOpenTime() == null
                && value.matches(".*\\d{4}[-/年].*")) {
            item.setOpenTime(normalizeDateTime(value));
        }
        // 开标地点：含"开评标地址"（过滤"详见"等引用文本）
        else if ((label.contains("开标地点") || label.contains("开评标地址")) && item.getOpenPlace() == null
                && !value.contains("详见")) {
            item.setOpenPlace(value);
        }
        // 招标人（以"招标人"开头，排除"招标人联系人"和"招标代理"）
        else if (label.startsWith("招标人") && !label.contains("联系人") && !label.contains("代理")
                && item.getTenderer() == null) {
            item.setTenderer(value);
        }
        // 招标人联系人
        else if ((label.equals("招标人联系人") || label.equals("联系人")) && item.getTenderContact() == null) {
            item.setTenderContact(value);
        }
        // 联系电话（第一次出现的通常是招标人的）
        else if (label.equals("联系电话") && item.getTenderTel() == null) {
            item.setTenderTel(value);
        }
        // 联系地址作为开标地点 fallback
        else if (label.equals("联系地址") && item.getOpenPlace() == null) {
            item.setOpenPlace(value);
        }
    }
}

