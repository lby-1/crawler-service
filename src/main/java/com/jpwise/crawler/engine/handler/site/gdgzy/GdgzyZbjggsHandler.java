package com.jpwise.crawler.engine.handler.site.gdgzy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 广东省公共资源交易中心 - 中标结果公示处理器
 * 使用新版 API：trading-notice/new/singleNode + trading-notice/new/detail
 */
@Slf4j
@Component
public class GdgzyZbjggsHandler implements CrawlHandler {

    private static final String[] SITE_CODES = {"gdgzy", "gdygp-slgc"};
    private static final String[] CATEGORY_CODES = {"zbjggs", "3C21", "3C22", "3C23", "3C24", "3C52"};
    private static final String BASE_API = "https://ygp.gdzwfw.gov.cn/ggzy-portal/center/apis";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, String> nodeIdCache = new ConcurrentHashMap<>();

    @Autowired
    private com.jpwise.crawler.engine.ApiCrawlEngine apiCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        boolean siteMatch = false;
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) { siteMatch = true; break; }
        }
        if (!siteMatch) return false;
        if (categoryCode == null) return false;
        for (String cat : categoryCode.split(",")) {
            for (String supported : CATEGORY_CODES) {
                if (supported.equals(cat.trim())) return true;
            }
        }
        return false;
    }

    @Override
    public String getHandlerName() {
        return "广东省交易中心-中标结果公示处理器";
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getName(), maxPages);
        List<CrawlItem> items = apiCrawlEngine.crawlList(site, category, maxPages, reqInterval);
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());
        crawlDetailByNewApi(item, reqInterval);
        log.info("[{}] 详情获取完成: 项目={}, 中标人={}, 中标价={}, 招标人={}",
            getHandlerName(), item.getProjectName(), item.getBidWinner(), item.getBidAmount(), item.getTenderer());
    }

    private void crawlDetailByNewApi(CrawlItem item, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) return;
        try {
            if (reqInterval > 0) Thread.sleep(reqInterval * 1000L);

            Map<String, String> params = parseQueryParams(java.net.URI.create(item.getSourceUrl()).toURL().getQuery());
            String siteCode = params.getOrDefault("siteCode", "");
            String citySiteCode = siteCode.length() >= 4 ? siteCode.substring(0, 4) + "00" : siteCode;
            String bizCode = params.getOrDefault("tradingProcess", "");
            String noticeId = params.getOrDefault("noticeId", "");
            String projectCode = params.getOrDefault("projectCode", "");
            String edition = "v3";
            java.util.regex.Matcher edM = java.util.regex.Pattern.compile("/trading-notice/(v\\d+)/").matcher(item.getSourceUrl());
            if (edM.find()) edition = edM.group(1);

            // 1. 获取 nodeId（市级siteCode）
            String cacheKey = citySiteCode + "|A|" + bizCode + "|A07";
            String nodeId = nodeIdCache.computeIfAbsent(cacheKey, k -> fetchNodeId(citySiteCode, bizCode));
            if (nodeId == null || nodeId.isBlank()) {
                log.warn("[{}] 无法获取nodeId, 跳过详情", getHandlerName());
                return;
            }

            // 2. 调用 new/detail API
            String detailUrl = BASE_API + "/trading-notice/new/detail?version=" + edition
                    + "&tradingType=A&noticeId=" + noticeId + "&bizCode=" + bizCode
                    + "&projectCode=" + projectCode + "&siteCode=" + citySiteCode + "&nodeId=" + nodeId;

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(detailUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;

            JSONObject json = JSON.parseObject(response.body());
            if (json == null || json.getIntValue("errcode") != 0) {
                log.warn("[{}] 详情API错误: {}", getHandlerName(), json != null ? json.getString("errmsg") : "null");
                return;
            }

            JSONObject data = json.getJSONObject("data");
            if (data == null) return;

            // 3. 结构化数据提取
            extractFromStructuredData(item, data);
            // 4. richtext → detail/summary
            extractRichtextContent(item, data);

            // 5. 项目名称 fallback: 从标题提取
            if (item.getProjectName() == null && item.getTitle() != null) {
                String title = item.getTitle();
                for (String suffix : List.of("中标结果公示", "中标候选人公示", "中标公示")) {
                    int idx = title.indexOf(suffix);
                    if (idx > 0) { title = title.substring(0, idx).trim(); break; }
                }
                if (!title.isBlank()) item.setProjectName(title);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[{}] 详情获取异常: {} - {}", getHandlerName(), item.getTitle(), e.getMessage());
        }
    }

    private String fetchNodeId(String siteCode, String bizCode) {
        try {
            String nodeUrl = BASE_API + "/trading-notice/new/singleNode?siteCode=" + siteCode
                    + "&tradingType=A&bizCode=" + bizCode + "&classify=A07";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(nodeUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "*/*")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body().trim();
            log.debug("[{}] singleNode(siteCode={}) 响应: {}", getHandlerName(), siteCode, body);
            if (body.matches("\\d+")) return body;
            JSONObject j = JSON.parseObject(body);
            if (j != null && j.get("data") != null) return j.getString("data");
            return null;
        } catch (Exception e) {
            log.warn("[{}] 获取nodeId失败(siteCode={}): {}", getHandlerName(), siteCode, e.getMessage());
            return null;
        }
    }

    private void extractFromStructuredData(CrawlItem item, JSONObject data) {
        JSONArray columns = data.getJSONArray("tradingNoticeColumnModelList");
        if (columns == null) return;
        for (int i = 0; i < columns.size(); i++) {
            JSONObject col = columns.getJSONObject(i);
            if (col == null) continue;
            JSONArray multiKvTable = col.getJSONArray("multiKeyValueTableList");
            if (multiKvTable == null) continue;
            for (int j = 0; j < multiKvTable.size(); j++) {
                JSONArray kvList = multiKvTable.getJSONArray(j);
                if (kvList == null) continue;
                for (int k = 0; k < kvList.size(); k++) {
                    JSONObject kv = kvList.getJSONObject(k);
                    if (kv == null) continue;
                    String code = kv.getString("code");
                    String key = kv.getString("key");
                    String value = kv.getString("value");
                    if (value == null || value.isBlank()) continue;

                    if (code != null) {
                        switch (code) {
                            case "TENDER_PROJECT_NAME" -> { if (item.getProjectName() == null) item.setProjectName(value); }
                            case "BID_SECTION_NAME" -> { if (item.getSectionName() == null) item.setSectionName(value); }
                            case "WIN_BIDDER_NAME" -> { if (item.getBidWinner() == null) item.setBidWinner(value); }
                            case "WIN_BID_PRICE", "BID_AMOUNT" -> { if (item.getBidAmount() == null) item.setBidAmount(value); }
                            case "TENDERER_NAME", "PURCHASER_NAME" -> { if (item.getTenderer() == null) item.setTenderer(value); }
                            case "TENDERER_CONTACT" -> { if (item.getTenderContact() == null) item.setTenderContact(value); }
                            case "TENDERER_PHONE" -> { if (item.getTenderTel() == null) item.setTenderTel(value); }
                            default -> {}
                        }
                    }
                    if (key != null) {
                        if (key.contains("项目名称") && item.getProjectName() == null) item.setProjectName(value);
                        else if (key.contains("标段") && key.contains("名称") && item.getSectionName() == null) item.setSectionName(value);
                        else if ((key.contains("中标人") || key.contains("中标单位")) && item.getBidWinner() == null) item.setBidWinner(value);
                        else if (key.contains("中标价") && item.getBidAmount() == null) item.setBidAmount(value.replaceAll("[^\\d.]", ""));
                        else if (key.contains("招标人") && !key.contains("代理") && item.getTenderer() == null) item.setTenderer(value);
                        else if (key.contains("中标日期") && item.getBidDate() == null) item.setBidDate(normalizeDateTime(value));
                    }
                }
            }
        }
    }

    private void extractRichtextContent(CrawlItem item, JSONObject data) {
        JSONArray columns = data.getJSONArray("tradingNoticeColumnModelList");
        if (columns == null) return;
        StringBuilder htmlBuilder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            JSONObject col = columns.getJSONObject(i);
            if (col == null) continue;
            String richtext = col.getString("richtext");
            if (richtext != null && !richtext.isBlank()) htmlBuilder.append(richtext);
        }
        if (!htmlBuilder.isEmpty()) {
            Document doc = Jsoup.parse(htmlBuilder.toString());
            String textContent = doc.text();
            item.setDetail(textContent);
            item.setSummary(textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent);
            extractFromTable(item, doc.body());
            // 纯文本 fallback（中标结果类：中标人、中标价、招标人等）
            extractFromPlainText(item, textContent);
        }
    }

    private void extractFromPlainText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "(?:招标人|招标单位)[：:：\\s]+([^，。,;；\\s\\n]{2,50}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "(?:中标人|中标单位|中标供应商)[：:：\\s]+([^，。,;；\\s\\n]{2,50}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setBidWinner(val);
        }
        if (item.getBidAmount() == null) {
            String val = regexFirst(text, "(?:中标价|中标金额|成交价)[（(]?万?元?[）)]?[：:：\\s]*([\\d,\\.]+)");
            if (val != null) item.setBidAmount(val.replace(",", ""));
        }
        if (item.getTenderContact() == null) {
            String val = regexFirst(text, "联系人[：:：\\s]+([^，。,;；\\s\\n]{2,20}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setTenderContact(val);
        }
        if (item.getTenderTel() == null) {
            String val = regexFirst(text, "联系电话[：:：\\s]*([\\d\\-()（）]{7,20})");
            if (val != null) item.setTenderTel(val);
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
     * 统一日期格式为 yyyy-MM-dd HH:mm:ss
     */
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
            String hour = String.format("%02d", Integer.parseInt(tm.group(1)));
            String min = String.format("%02d", Integer.parseInt(tm.group(2)));
            String sec = tm.group(3) != null ? String.format("%02d", Integer.parseInt(tm.group(3))) : "00";
            return datePart + " " + hour + ":" + min + ":" + sec;
        }
        return datePart;
    }

    private void extractFromTable(CrawlItem item, Element container) {
        Elements tables = container.select("table");
        for (Element table : tables) {
            for (Element row : table.select("tr")) {
                Elements cells = row.select("td, th");
                if (cells.size() >= 2) {
                    String label = cells.get(0).text().trim();
                    String value = cells.get(1).text().trim();
                    if (value.isBlank()) continue;
                    if ((label.contains("中标单位") || label.contains("中标人")) && item.getBidWinner() == null) item.setBidWinner(value);
                    else if (label.contains("中标价") && item.getBidAmount() == null) item.setBidAmount(value.replaceAll("[^\\d.]", ""));
                    else if (label.contains("招标人") && !label.contains("代理") && item.getTenderer() == null) item.setTenderer(value);
                    else if (label.contains("项目名称") && item.getProjectName() == null) item.setProjectName(value);
                    else if (label.contains("标段") && item.getSectionName() == null) item.setSectionName(value);
                    else if (label.contains("中标日期") && item.getBidDate() == null) item.setBidDate(normalizeDateTime(value));
                }
            }
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq),
                    eq < pair.length() - 1 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "");
            }
        }
        return params;
    }
}
