package com.jpwise.crawler.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * API 型爬取引擎 — 适用于 SPA 网站，通过 JSON API 获取数据
 * 典型站点：广东省公共资源交易平台 (ygp.gdzwfw.gov.cn)
 *
 * 配置 application.yml 时，设置 engine-type: api，并在 api-config 中配置：
 *   api-url:         POST 列表接口地址
 *   detail-api-url:  GET 详情接口地址（可选）
 *   request-body:    请求体模板（JSON字符串，{pageNo}/{pageSize} 为占位符）
 *   data-path:       响应中数据列表的 JSON Path（如 data.pageData）
 *   total-path:      响应中总数的 JSON Path（如 data.total）
 *   field-mapping:   字段映射（title, url, publishTime, siteName 等）
 */
@Slf4j
@Component
public class ApiCrawlEngine {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_RETRY = 2;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 通过 API 爬取列表数据
     */
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                     CrawlerProperties.CategoryConfig category,
                                     int maxPages, int reqInterval) {
        List<CrawlItem> allItems = new ArrayList<>();
        CrawlerProperties.ApiConfig api = site.getApiConfig();
        if (api == null) {
            log.error("站点 {} 未配置 apiConfig", site.getName());
            return allItems;
        }

        int pageSize = api.getPageSize() > 0 ? api.getPageSize() : 10;

        for (int page = 1; page <= Math.max(1, maxPages); page++) {
            log.info("正在爬取API: {} - {} (第{}页)", site.getName(), category.getName(), page);

            try {
                // 构建请求URL和请求体（支持URL中的占位符替换）
                String apiUrl = api.getApiUrl()
                        .replace("{categoryCode}", category.getCode() != null ? category.getCode() : "")
                        .replace("{categoryUrl}", category.getUrl() != null ? category.getUrl() : "");
                String body = buildRequestBody(api, category, page, pageSize);
                String responseStr = doPost(apiUrl, body);

                if (responseStr == null) {
                    log.warn("API请求失败，跳过");
                    break;
                }

                // 解析响应
                JSONObject response = JSON.parseObject(responseStr);
                JSONArray dataArray = getByPath(response, api.getDataPath());
                if (dataArray == null || dataArray.isEmpty()) {
                    log.info("第{}页无数据，停止翻页", page);
                    break;
                }

                // 提取每条数据
                Map<String, String> fieldMap = api.getFieldMapping();
                for (int i = 0; i < dataArray.size(); i++) {
                    JSONObject item = dataArray.getJSONObject(i);
                    CrawlItem crawlItem = mapToCrawlItem(item, fieldMap, site, category);
                    if (crawlItem != null) {
                        allItems.add(crawlItem);
                    }
                }

                log.info("第{}页获取{}条数据", page, dataArray.size());

                if (page < maxPages && reqInterval > 0) {
                    Thread.sleep(reqInterval * 1000L);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("API爬取异常: {} - {}", site.getName(), category.getName(), e);
                break;
            }
        }

        return allItems;
    }

    private String buildRequestBody(CrawlerProperties.ApiConfig api,
                                    CrawlerProperties.CategoryConfig category,
                                    int pageNo, int pageSize) {
        String template = api.getRequestBody();
        if (template == null || template.isBlank()) {
            template = "{}";
        }
        // 替换占位符
        return template
                .replace("{pageNo}", String.valueOf(pageNo))
                .replace("{pageSize}", String.valueOf(pageSize))
                .replace("{categoryCode}", category.getCode() != null ? category.getCode() : "")
                .replace("{categoryUrl}", category.getUrl() != null ? category.getUrl() : "");
    }

    private CrawlItem mapToCrawlItem(JSONObject data, Map<String, String> fieldMap,
                                     CrawlerProperties.SiteConfig site,
                                     CrawlerProperties.CategoryConfig category) {
        if (fieldMap == null || fieldMap.isEmpty()) return null;

        CrawlItem item = new CrawlItem();
        // Title: 支持多个候选字段（用逗号分隔），按顺序尝试
        String title = getFieldWithFallbacks(data, fieldMap.get("title"));
        item.setTitle(title);
        // publishTime: 支持多个候选字段（用逗号分隔），按顺序尝试
        item.setPublishTime(formatDate(getFieldWithFallbacks(data, fieldMap.get("publishTime"))));
        item.setCategory(category.getName());
        item.setCategoryCode(category.getCode());
        item.setSiteName(site.getName());

        // 省份/城市：优先从API数据取，fallback到站点配置
        String province = getField(data, fieldMap.getOrDefault("province", ""));
        item.setProvince(province != null && !province.isBlank() ? province : site.getProvince());
        String city = getField(data, fieldMap.getOrDefault("city", ""));
        item.setCity(city != null && !city.isBlank() ? city : site.getCity());

        // 构建详情URL（替换数据字段占位符 + category占位符）
        String detailUrl = buildDetailUrl(data, fieldMap, site, "detailUrl");
        if (detailUrl != null) {
            detailUrl = detailUrl
                    .replace("{categoryCode}", category.getCode() != null ? category.getCode() : "")
                    .replace("{categoryName}", urlEncode(category.getName()));
        }
        item.setSourceUrl(detailUrl);
        
        // 构建浏览器真实展示用的前台页面URL（只有配置了browserUrl模板时才构建）
        String browserUrlTemplate = fieldMap != null ? fieldMap.get("browserUrl") : null;
        if (browserUrlTemplate != null && !browserUrlTemplate.isBlank()) {
            String browserUrl = buildDetailUrl(data, fieldMap, site, "browserUrl");
            if (browserUrl != null) {
                browserUrl = browserUrl
                        .replace("{categoryCode}", category.getCode() != null ? category.getCode() : "")
                        .replace("{categoryName}", urlEncode(category.getName()));
                item.setWebUrl(browserUrl);
            }
        }

        // 摘要
        String summary = getField(data, fieldMap.get("summary"));
        if (summary == null || summary.isBlank()) {
            // 拼接几个关键字段作为摘要
            StringBuilder sb = new StringBuilder();
            for (String key : List.of("projectOwner", "siteName", "datasetName", "pubServicePlat")) {
                String val = getField(data, fieldMap.getOrDefault(key, key));
                if (val != null && !val.isBlank()) {
                    if (!sb.isEmpty()) sb.append(" | ");
                    sb.append(val);
                }
            }
            item.setSummary(sb.toString());
        } else {
            item.setSummary(summary);
        }

        // URL Hash
        if (item.getSourceUrl() != null) {
            item.setUrlHash(DigestUtils.md5Hex(item.getSourceUrl()));
        } else {
            // 用标题+发布时间做hash
            item.setUrlHash(DigestUtils.md5Hex(item.getTitle() + item.getPublishTime()));
        }

        return item;
    }

    private String buildDetailUrl(JSONObject data, Map<String, String> fieldMap,
                                  CrawlerProperties.SiteConfig site, String urlKey) {
        String detailUrlTemplate = fieldMap != null ? fieldMap.get(urlKey) : null;

        if (detailUrlTemplate != null && detailUrlTemplate.contains("{")) {
            // 模板URL，替换字段值
            String url = detailUrlTemplate;
            
            // 针对广东特殊处理：从 projectCode 提取稳定的市级 siteCode (避免区县级导致的前端校验失败)
            if (url.contains("{sysSiteCode}")) {
                String pCode = data.getString("projectCode");
                if (pCode != null && pCode.length() >= 7) {
                    // 如 E440811... 截取 4408 后拼接 00 变成市级 440800
                    String cityLevelCode = pCode.substring(1, 5) + "00";
                    url = url.replace("{sysSiteCode}", cityLevelCode);
                }
            }
            
            // Step 1: 先替换直接引用JSON字段的占位符（如 {doc_pub_url}, {guid}）
            for (String key : data.keySet()) {
                String placeholder = "{" + key + "}";
                if (url.contains(placeholder)) {
                    String val = data.getString(key);
                    if (val != null) {
                        // 如果值已经是完整URL（以http://或https://开头），则不进行URL编码
                        String replacement = isFullUrl(val) ? val : urlEncode(val);
                        url = url.replace(placeholder, replacement);
                    }
                }
            }
            
            // Step 2: 再替换 fieldMap 中定义的字段映射（如 {noticeId} -> fieldMap.get("noticeId") = "guid"）
            for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
                // 跳过 urlKey 本身（如 detailUrl）
                if (entry.getKey().equals(urlKey)) continue;
                
                String placeholder = "{" + entry.getKey() + "}";
                if (url.contains(placeholder)) {
                    String val = getField(data, entry.getValue());
                    if (val != null) {
                        // 如果值已经是完整URL（以http://或https://开头），则不进行URL编码
                        String replacement = isFullUrl(val) ? val : urlEncode(val);
                        url = url.replace(placeholder, replacement);
                    }
                }
            }
            
            // 清理所有残留的未匹配的占位符，防止形如 {nodeId} 的字符串破坏 URL
            // 但保留 {categoryName} 和 {categoryCode}，它们在 mapToCrawlItem 中后续替换
            url = url.replaceAll("\\{(?!categoryName|categoryCode)[^}]*\\}", "");
            
            // 如果 URL 不为空，直接返回
            if (!url.isBlank()) {
                return url;
            }
        }

        // 如果有 detailUrl/browserUrl 模板但替换失败，不再 fallback 到 noticeId 拼接
        // 只有在完全没配置模板时才用 noticeId fallback
        if (detailUrlTemplate == null) {
            String noticeId = getField(data, fieldMap != null ? fieldMap.getOrDefault("noticeId", "noticeId") : "noticeId");
            if (noticeId != null) {
                return site.getBaseUrl() + "/notice/detail?noticeId=" + noticeId;
            }
        }
        return null;
    }

    private String getField(JSONObject data, String fieldName) {
        if (data == null || fieldName == null || fieldName.isBlank()) return null;
        // 支持嵌套路径: "a.b.c"
        if (fieldName.contains(".")) {
            String[] parts = fieldName.split("\\.");
            JSONObject current = data;
            for (int i = 0; i < parts.length - 1; i++) {
                current = current.getJSONObject(parts[i]);
                if (current == null) return null;
            }
            return current.getString(parts[parts.length - 1]);
        }
        return data.getString(fieldName);
    }

    /**
     * 支持多个候选字段（用逗号分隔），按顺序尝试，返回第一个非空值
     * 例如: "bulletinname,tenderProjectName" 会依次尝试 bulletinname、tenderProjectName
     */
    private String getFieldWithFallbacks(JSONObject data, String fieldNames) {
        if (data == null || fieldNames == null || fieldNames.isBlank()) return null;
        String[] candidates = fieldNames.split(",");
        for (String candidate : candidates) {
            String val = getField(data, candidate.trim());
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private JSONArray getByPath(JSONObject root, String path) {
        if (root == null || path == null) return null;
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (current instanceof JSONObject obj) {
                current = obj.get(part);
            } else {
                return null;
            }
        }
        return current instanceof JSONArray arr ? arr : null;
    }

    /**
     * 检查字符串是否已经是完整的URL（以http://或https://开头）
     */
    private boolean isFullUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private String urlEncode(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private String formatDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return null;
        // 处理 "20260317112800" 格式
        if (rawDate.length() == 14 && rawDate.matches("\\d{14}")) {
            try {
                LocalDateTime dt = LocalDateTime.parse(rawDate, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                return rawDate;
            }
        }
        return rawDate;
    }

    private String doPost(String url, String body) {
        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                log.warn("API返回非200: status={}, url={}", response.statusCode(), url);
            } catch (IOException e) {
                log.warn("API请求失败(第{}次): {} - {}", retry + 1, url, e.getMessage());
                if (retry < MAX_RETRY) {
                    try { Thread.sleep(2000L * (retry + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
