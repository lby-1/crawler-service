package com.jpwise.crawler.engine;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * 通用可配置爬取引擎
 * 通过 CSS 选择器配置适配不同网站，无需为每个站点写代码。
 *
 * 配置示例（application.yml）：
 *   sites:
 *     gzggzy:
 *       base-url: https://www.gzggzy.cn
 *       list-selectors:
 *         row: "table.public-table tr"
 *         title: "td a.title"
 *         title-attr: "attr:title"
 *         date: "td span"
 *       detail-selectors:
 *         content: "div.detail-content-main"
 *         publish-time: "meta[name=PubDate]"
 *         publish-time-attr: "attr:content"
 */
@Slf4j
@Component
public class CrawlEngine {

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int MAX_RETRY = 2;

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    );

    private final Random random = new Random();

    /**
     * 爬取列表页
     */
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                     CrawlerProperties.CategoryConfig category,
                                     int maxPages, int reqInterval) {
        List<CrawlItem> allItems = new ArrayList<>();
        CrawlerProperties.ListSelectors sel = site.getListSelectors();

        for (int page = 1; page <= Math.max(1, maxPages); page++) {
            String pageUrl = buildPageUrl(category.getUrl(), site.getPagePattern(), page);
            log.info("正在爬取列表页: {} (第{}页)", pageUrl, page);

            try {
                Document doc = fetchDocument(pageUrl);
                if (doc == null) {
                    log.warn("列表页获取失败，跳过: {}", pageUrl);
                    break;
                }

                List<CrawlItem> pageItems = parseListPage(doc, site, category);
                if (pageItems.isEmpty()) {
                    log.info("第{}页无数据，停止翻页", page);
                    break;
                }

                allItems.addAll(pageItems);
                log.info("第{}页获取{}条数据", page, pageItems.size());

                if (page < maxPages && reqInterval > 0) {
                    Thread.sleep(reqInterval * 1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("爬取列表页异常: {}", pageUrl, e);
                break;
            }
        }

        return allItems;
    }

    /**
     * 爬取详情页
     */
    public CrawlItem crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
            return item;
        }

        String detailUrl = ensureAbsoluteUrl(item.getSourceUrl(), site.getBaseUrl());

        try {
            if (reqInterval > 0) {
                Thread.sleep(reqInterval * 1000L);
            }

            Document doc = fetchDocument(detailUrl);
            if (doc == null) return item;

            CrawlerProperties.DetailSelectors sel = site.getDetailSelectors();

            // 提取正文
            Element contentEl = null;
            if (sel.getContent() != null && !sel.getContent().isBlank()) {
                contentEl = doc.selectFirst(sel.getContent());
            }

            if (contentEl != null) {
                item.setDetail(contentEl.text());
                item.setSummary(truncate(contentEl.text(), 500));

                // 从 HTML 表格中直接提取结构化字段（比正则更准确）
                extractStructuredFromTable(contentEl, item);
            } else {
                // 备选：尝试从整个文档或表格容器中提取
                log.warn("主内容选择器 '{}' 未找到，尝试备选提取策略", sel.getContent());

                // 策略A：查找表格容器（如中标结果页）
                Element tableContainer = doc.selectFirst("table.public-table, table.form-table, div.portlet");
                if (tableContainer != null) {
                    item.setDetail(tableContainer.text());
                    item.setSummary(truncate(tableContainer.text(), 500));
                    extractStructuredFromTable(tableContainer, item);
                    log.info("从表格容器提取到内容: {}", item.getTitle());
                } else {
                    // 策略B：使用 body 作为后备
                    item.setDetail(doc.body().text());
                    item.setSummary(truncate(doc.body().text(), 500));
                    extractStructuredFromTable(doc.body(), item);
                }
            }

            // 提取发布时间
            if (sel.getPublishTime() != null && !sel.getPublishTime().isBlank()) {
                Element timeEl = doc.selectFirst(sel.getPublishTime());
                if (timeEl != null) {
                    String timeText = extractValue(timeEl, sel.getPublishTimeAttr());
                    if (timeText != null && !timeText.isBlank()) {
                        item.setPublishTime(timeText.length() > 19 ? timeText.substring(0, 19) : timeText);
                    }
                }
            }

            // 招标公告类：尝试从正文末尾提取招标人（常见格式：落款处写单位名称）
            if (item.getTenderer() == null) {
                extractTendererFromText(doc, item);
            }

            item.setSourceUrl(detailUrl);
            log.debug("详情获取成功: {}", item.getTitle());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("详情页获取失败: {} - {}", detailUrl, e.getMessage());
        }

        return item;
    }

    /**
     * 从 HTML 表格中提取结构化字段（适用于中标公示等表格格式页面）
     * 遍历所有 <tr>，第一个 <td> 是标签，第二个 <td> 是值
     */
    private void extractStructuredFromTable(Element contentEl, CrawlItem item) {
        // 查找所有表格（包括 template/shadow DOM 中的内容）
        Elements tables = contentEl.select("table");
        if (tables.isEmpty()) {
            log.debug("未找到表格元素");
            return;
        }
        log.debug("找到 {} 个表格", tables.size());

        // 构建标签→值的映射
        Map<String, String> kvMap = new java.util.LinkedHashMap<>();
        for (Element table : tables) {
            Elements rows = table.select("tr");
            for (int i = 0; i < rows.size(); i++) {
                Element row = rows.get(i);
                Elements cells = row.select("td, th");  // 同时支持 td 和 th

                if (cells.size() >= 2) {
                    // 检查是否是"标签行"（全是tdTitle类）
                    boolean isAllTitleCells = true;
                    for (Element cell : cells) {
                        if (!cell.hasClass("tdTitle") && !cell.hasClass("title")) {
                            isAllTitleCells = false;
                            break;
                        }
                    }

                    // 如果是标签行，且下一行有相同数量的单元格，则配对提取
                    if (isAllTitleCells && i + 1 < rows.size()) {
                        Elements nextRowCells = rows.get(i + 1).select("td, th");
                        if (nextRowCells.size() == cells.size()) {
                            log.debug("检测到标签行+值行格式，{}个字段", cells.size());
                            for (int j = 0; j < cells.size(); j++) {
                                String label = cells.get(j).text().trim();
                                String value = nextRowCells.get(j).text().trim();
                                if (!label.isBlank() && !value.isBlank()) {
                                    kvMap.put(label, value);
                                    log.debug("表格字段(标签行+值行): {} = {}", label,
                                            value.length() > 30 ? value.substring(0, 30) + "..." : value);
                                }
                            }
                            i++; // 跳过下一行（值行）
                            continue;
                        }
                    }

                    // 标准格式：标签|值在同一行
                    String label = cells.get(0).text().trim();
                    String value = cells.get(1).text().trim();
                    if (!label.isBlank() && !value.isBlank()) {
                        kvMap.put(label, value);
                        log.debug("表格字段: {} = {}", label, value.length() > 30 ? value.substring(0, 30) + "..." : value);
                    }
                    // 4列格式：标签1|值1|标签2|值2（如"招标人|XXX|招标代理|YYY"）
                    if (cells.size() >= 4) {
                        String label2 = cells.get(2).text().trim();
                        String value2 = cells.get(3).text().trim();
                        if (!label2.isBlank() && !value2.isBlank()) {
                            kvMap.put(label2, value2);
                            log.debug("表格字段(4列): {} = {}", label2, value2.length() > 30 ? value2.substring(0, 30) + "..." : value2);
                        }
                    }
                }
            }
        }

        if (kvMap.isEmpty()) {
            log.debug("表格中未提取到键值对");
            return;
        }
        log.info("从表格提取到 {} 个键值对", kvMap.size());

        // 按标签名映射到 CrawlItem 字段
        int matchedFields = 0;

        // 先遍历一遍，优先提取招标项目名称（避免被投资项目名称覆盖）
        for (Map.Entry<String, String> entry : kvMap.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue();

            // 优先匹配"招标项目名称"
            if (label.contains("招标项目名称")) {
                item.setProjectName(value);
                matchedFields++;
                log.info("提取招标项目名称(优先): {}", value);
                break;
            }
        }

        for (Map.Entry<String, String> entry : kvMap.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue();

            // 标准化标签（去除空格、括号等）用于匹配
            String normalizedLabel = label.replaceAll("[（(）)\\s]", "");

            // 项目名称：优先招标项目名称，其次投资项目名称，最后其他项目名称
            if (label.contains("招标项目名称")) {
                // 已在上面的循环处理，跳过
                continue;
            } else if (label.contains("投资项目名称") && item.getProjectName() == null) {
                item.setProjectName(value);
                matchedFields++;
                log.debug("提取投资项目名称: {}", value);
            } else if (label.contains("项目名称") && item.getProjectName() == null) {
                item.setProjectName(value);
                matchedFields++;
                log.debug("提取项目名称: {}", value);
            } else if (containsAny(label, "标段", "包") || normalizedLabel.contains("标段") || normalizedLabel.contains("包名称")) {
                if (item.getSectionName() == null) {
                    item.setSectionName(value);
                    matchedFields++;
                    log.info("提取标段名称: {}", value);
                }
            } else if (containsAny(label, "最高投标限价", "最高限价", "控制价")) {
                if (item.getMaxPrice() == null) item.setMaxPrice(value);
            } else if (containsAny(label, "开标时间")) {
                if (item.getOpenTime() == null) item.setOpenTime(value);
            } else if (containsAny(label, "开标地点")) {
                if (item.getOpenPlace() == null) item.setOpenPlace(value);
            } else if (label.contains("招标人") && !label.contains("代理")) {
                if (item.getTenderer() == null) item.setTenderer(value);
            } else if (containsAny(label, "招标代理") || normalizedLabel.contains("招标代理")) {
                // 招标代理单独处理，不覆盖招标人
                log.debug("招标代理: {}", value);
            } else if (containsAny(label, "联系人") && item.getTenderContact() == null) {
                item.setTenderContact(value);
            } else if (containsAny(label, "联系电话", "电话") && item.getTenderTel() == null) {
                item.setTenderTel(value);
            } else if (containsAny(label, "中标单位") || normalizedLabel.equals("中标单位")) {
                // 严格匹配"中标单位"，避免与"中标价"混淆
                if (item.getBidWinner() == null) {
                    item.setBidWinner(value);
                    matchedFields++;
                    log.info("提取中标人: {}", value);
                }
            } else if (containsAny(label, "中标价", "中标金额", "成交价", "成交金额") || label.contains("中标价")) {
                // 严格匹配包含"中标价"的标签
                if (item.getBidAmount() == null) {
                    // 提取数字部分（如 "中标总价(万元):2661.448507" → "2661.448507"）
                    String amount = extractAmountFromText(value);
                    item.setBidAmount(amount);
                    matchedFields++;
                    log.info("提取中标金额: {} (原始: {})", amount, value);
                }
            } else if (containsAny(label, "中标日期") || normalizedLabel.contains("中标日期")) {
                if (item.getBidDate() == null) {
                    item.setBidDate(normalizeDateTime(value));
                    matchedFields++;
                    log.info("提取中标日期: {}", normalizeDateTime(value));
                }
            }
        }
        log.info("表格字段映射完成: {}/{} 个字段匹配", matchedFields, kvMap.size());
    }

    /**
     * 从金额文本中提取数字
     * 支持格式："中标总价(万元):2661.448507" → "2661.448507"
     *         "2661.448507万元" → "2661.448507"
     */
    private String extractAmountFromText(String text) {
        if (text == null || text.isBlank()) return text;
        // 先尝试匹配冒号后的数字（如 "中标总价(万元):2661.448507"）
        java.util.regex.Pattern colonPattern = java.util.regex.Pattern.compile(":\\s*([\\d,]+\\.?\\d*)");
        java.util.regex.Matcher colonMatcher = colonPattern.matcher(text);
        if (colonMatcher.find()) {
            String amount = colonMatcher.group(1).replace(",", "");
            log.debug("从金额文本提取(冒号格式): {} → {}", text, amount);
            return amount;
        }
        // 否则匹配任意数字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\d,]+\\.?\\d*");
        java.util.regex.Matcher matcher = pattern.matcher(text.replace(",", ""));
        if (matcher.find()) {
            String amount = matcher.group().replace(",", "");
            log.debug("从金额文本提取: {} → {}", text, amount);
            return amount;
        }
        log.debug("从金额文本提取失败，返回原文: {}", text);
        return text;
    }

    /**
     * 从中标结果公示纯文本中提取字段
     * 适用于内容用<p>标签展示的页面（如广州中标结果公示）
     */
    private void extractBidResultFromText(String text, CrawlItem item) {
        log.debug("尝试从中标结果公示文本提取字段");

        // 标段名称 - 匹配 "标段名称：XXX" 或 "标段(包)名称：XXX"
        if (item.getSectionName() == null || item.getSectionName().isBlank()) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("标段[\\(（]包[\\)）]?名称[：:\\s]+(.+?)(?=\\n|$)");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                item.setSectionName(matcher.group(1).trim());
                log.info("从文本提取标段名称: {}", item.getSectionName());
            }
        }

        // 中标人 - 匹配 "中标人：XXX"
        if (item.getBidWinner() == null || item.getBidWinner().isBlank()) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("中标人[：:\\s]+([^\\n，。]+?)(?=\\n|中标价|$)");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                item.setBidWinner(matcher.group(1).trim());
                log.info("从文本提取中标人: {}", item.getBidWinner());
            }
        }

        // 中标价 - 匹配 "中标价：XXX元"
        if (item.getBidAmount() == null || item.getBidAmount().isBlank()) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("中标价[：:\\s]+([\\d,\\.]+)[元万元]");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                item.setBidAmount(matcher.group(1).replace(",", ""));
                log.info("从文本提取中标价: {}", item.getBidAmount());
            }
        }

        // 招标人 - 匹配 "招标人名称：XXX"
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("招标人[名称]*[：:\\s]+([^\\n，。（(]{2,50}?)(?=[\\n，。（(]|$)");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                item.setTenderer(matcher.group(1).trim());
                log.info("从文本提取招标人(中标公示): {}", item.getTenderer());
            }
        }

        // 中标日期 - 匹配 "定标时间：XXX" 或 "日期：XXX"
        if (item.getBidDate() == null || item.getBidDate().isBlank()) {
            // 先尝试定标时间
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("定标时间[：:\\s]*(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                item.setBidDate(String.format("%s-%02d-%02d", 
                    matcher.group(1), 
                    Integer.parseInt(matcher.group(2)), 
                    Integer.parseInt(matcher.group(3))));
                log.info("从文本提取中标日期(定标时间): {}", item.getBidDate());
            } else {
                // 再尝试日期
                pattern = java.util.regex.Pattern.compile("(?<!公示)日期[：:\\s]*(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
                matcher = pattern.matcher(text);
                if (matcher.find()) {
                    item.setBidDate(String.format("%s-%02d-%02d", 
                        matcher.group(1), 
                        Integer.parseInt(matcher.group(2)), 
                        Integer.parseInt(matcher.group(3))));
                    log.info("从文本提取中标日期: {}", item.getBidDate());
                }
            }
        }
    }

    /**
     * 从正文中提取招标人/招标单位/代理机构等（招标公告类页面）
     * 策略：
     *   1. 先用标签匹配：在全文中搜索"招标人："、"招标单位："、"代理单位："等标签后的内容
     *   2. 再用落款匹配：从正文末尾向前找单位名称（以组织后缀结尾的文本行）
     */
    private void extractTendererFromText(Document doc, CrawlItem item) {
        Element contentMain = doc.selectFirst("div.detail-content-main");
        if (contentMain == null) {
            log.warn("未找到内容区域 div.detail-content-main，跳过招标人提取");
            return;
        }

        String fullText = contentMain.text();
        log.debug("开始提取招标人，正文长度: {}, 标题: {}", fullText.length(), item.getTitle());

        // 如果是中标结果公示类页面，尝试提取更多字段
        if (item.getCategoryCode() != null && 
            (item.getCategoryCode().contains("zbjggs") || item.getCategoryCode().contains("zbxx"))) {
            extractBidResultFromText(fullText, item);
        }

        // ---- 策略1：标签匹配（"招标人：XXX"、"招标单位：XXX"等） ----
        // 按优先级排序：招标人 > 招标单位 > 建设单位 > 代理机构 > 代理单位
        String[] labelPatterns = {
                "招标人[名称]*[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s（(]|$)",
                "招标单位[（(全称)）]*[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s]|$)",
                "建设单位[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s]|$)",
                "代理机构[（(全称)）]*[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s]|$)",
                "代理单位[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s]|$)",
                "采购人[：::\\s]+([^，。,;；\\s\\d]{2,50}?)(?=[，。,;；\\s]|$)"
        };
        for (String pattern : labelPatterns) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(fullText);
                if (m.find()) {
                    String val = m.group(1).trim();
                    if (!val.isBlank() && val.length() >= 4) {
                        item.setTenderer(val);
                        log.info("从标签提取招标人: {} (模式: {})", val, pattern.substring(0, Math.min(6, pattern.length())));
                        return;
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        log.debug("策略1（标签匹配）未找到招标人");

        // ---- 策略2：落款匹配（从末尾向前找单位名称） ----
        // 获取所有块级子元素（p, div, span 等），不局限于 <p>
        Elements blocks = contentMain.select("p, div, span, td");
        log.debug("策略2：扫描 {} 个块级元素", blocks.size());

        // 扩展组织后缀列表，增加更多可能的落款单位后缀
        String orgSuffixes = "(?:中心|公司|局|处|院|所|厅|委|办|站|队|部|会|集团|总队|支队|大学|学院|学校|研究所|设计院|监理部|事务所|研究院|开发部|管理局|有限公司|分公司|子公司|营业部|项目部|工程部|指挥部|监督站)";

        int scanCount = 0;
        for (int i = blocks.size() - 1; i >= Math.max(0, blocks.size() - 20); i--) {
            String text = blocks.get(i).text().trim();
            scanCount++;

            // 跳过空段、"特此公告"等
            if (text.isBlank() || text.length() < 4 || text.length() > 100) {
                log.debug("  [{}] 跳过: 长度={}, 内容片段={}", i, text.length(),
                        text.length() > 20 ? text.substring(0, 20) + "..." : text);
                continue;
            }
            if (text.contains("特此公告") || text.contains("特此通知")) {
                log.debug("  [{}] 跳过: 包含特此公告/通知", i);
                continue;
            }

            log.debug("  [{}] 检查: {}", i, text);

            // 尝试提取日期（如果还没有publishTime）
            if (item.getPublishTime() == null || item.getPublishTime().isBlank()) {
                String datePattern = "^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$";
                java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile(datePattern).matcher(text);
                if (dateMatcher.find()) {
                    String formattedDate = String.format("%s-%02d-%02d",
                            dateMatcher.group(1),
                            Integer.parseInt(dateMatcher.group(2)),
                            Integer.parseInt(dateMatcher.group(3)));
                    item.setPublishTime(formattedDate);
                    log.info("从落款提取日期: {}", formattedDate);
                    continue;
                }
            }

            // 找到以组织后缀结尾的文本（如"广州市水务局"、"中水珠江公司"等）
            if (text.matches(".*" + orgSuffixes + "$")) {
                item.setTenderer(text);
                log.info("从落款提取招标人: {} (索引: {})", text, i);

                // 同时尝试从相邻元素提取日期（如果还没有publishTime）
                if (item.getPublishTime() == null || item.getPublishTime().isBlank()) {
                    extractPublishDateFromAdjacentBlocks(blocks, i, item);
                }
                return;
            }
        }
        log.warn("策略2（落款匹配）未找到招标人，扫描了 {} 个元素", scanCount);
    }

    /**
     * 从相邻块级元素中提取发布日期
     * 通常在落款处，日期和招标人会相邻出现（上下关系）
     */
    private void extractPublishDateFromAdjacentBlocks(Elements blocks, int tendererIndex, CrawlItem item) {
        // 检查前后3个元素范围内是否有日期
        for (int j = Math.max(0, tendererIndex - 3); j <= Math.min(blocks.size() - 1, tendererIndex + 3); j++) {
            if (j == tendererIndex) continue;
            String adjacentText = blocks.get(j).text().trim();
            String datePattern = "^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$";
            java.util.regex.Matcher dateMatcher = java.util.regex.Pattern.compile(datePattern).matcher(adjacentText);
            if (dateMatcher.find()) {
                String formattedDate = String.format("%s-%02d-%02d",
                        dateMatcher.group(1),
                        Integer.parseInt(dateMatcher.group(2)),
                        Integer.parseInt(dateMatcher.group(3)));
                item.setPublishTime(formattedDate);
                log.debug("从相邻落款提取日期: {}", formattedDate);
                return;
            }
        }
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

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 通用列表页解析 — 根据选择器配置提取数据
     */
    private List<CrawlItem> parseListPage(Document doc,
                                          CrawlerProperties.SiteConfig site,
                                          CrawlerProperties.CategoryConfig category) {
        List<CrawlItem> items = new ArrayList<>();
        CrawlerProperties.ListSelectors sel = site.getListSelectors();

        // 选择所有行
        Elements rows = doc.select(sel.getRow());

        for (Element row : rows) {
            // 找标题元素
            Element titleEl = row.selectFirst(sel.getTitle());
            if (titleEl == null) continue;

            CrawlItem item = new CrawlItem();

            // 提取标题文本
            String title = extractValue(titleEl, sel.getTitleAttr());
            if (title == null || title.isBlank()) {
                title = titleEl.text().trim();
            }
            item.setTitle(title);

            // 提取链接
            Element linkEl = (sel.getLink() != null && !sel.getLink().isBlank())
                    ? row.selectFirst(sel.getLink()) : titleEl;
            if (linkEl != null) {
                String href = linkEl.attr(sel.getLinkAttr());
                item.setSourceUrl(ensureAbsoluteUrl(href, site.getBaseUrl()));
            }

            // 提取日期
            if (sel.getDate() != null && !sel.getDate().isBlank()) {
                Element dateEl = row.selectFirst(sel.getDate());
                if (dateEl != null) {
                    item.setPublishTime(dateEl.text().trim());
                }
            }

            // 分类信息
            item.setCategory(category.getName());
            item.setCategoryCode(category.getCode());
            item.setSiteName(site.getName());
            item.setProvince(site.getProvince());
            item.setCity(site.getCity());

            // URL Hash 去重
            if (item.getSourceUrl() != null) {
                item.setUrlHash(DigestUtils.md5Hex(item.getSourceUrl()));
            }

            items.add(item);
        }

        return items;
    }

    /**
     * 从元素中按指定方式提取值
     * 格式：
     *   "text"          → element.text()
     *   "attr:title"    → element.attr("title")
     *   "attr:content"  → element.attr("content")
     *   "html"          → element.html()
     */
    private String extractValue(Element el, String mode) {
        if (el == null || mode == null) return null;
        if (mode.startsWith("attr:")) {
            return el.attr(mode.substring(5)).trim();
        }
        if ("html".equals(mode)) {
            return el.html().trim();
        }
        return el.text().trim();
    }

    /**
     * 构建分页URL
     * 根据 pagePattern 自动替换：
     *   "index.jhtml" → page=1: index.jhtml, page=2: index_2.jhtml
     *   "?page=1"     → page=1: ?page=1, page=2: ?page=2
     */
    private String buildPageUrl(String baseUrl, String pattern, int page) {
        if (page <= 1) return baseUrl;

        if (pattern == null || pattern.isBlank()) {
            pattern = "index.jhtml";
        }

        // 模式1：index.jhtml → index_N.jhtml（广州公共资源交易中心等）
        if (pattern.contains("index.")) {
            String pagePart = pattern.replace("index.", "index_" + page + ".");
            return baseUrl.replace(pattern, pagePart);
        }

        // 模式2：包含 {page} 占位符
        if (pattern.contains("{page}")) {
            return baseUrl + pattern.replace("{page}", String.valueOf(page));
        }

        // 模式3：?page=N 参数
        if (baseUrl.contains("?")) {
            return baseUrl + "&page=" + page;
        }
        return baseUrl + "?page=" + page;
    }

    private String ensureAbsoluteUrl(String url, String baseUrl) {
        if (url == null) return null;
        if (url.startsWith("http")) return url;
        return baseUrl + (url.startsWith("/") ? "" : "/") + url;
    }

    private Document fetchDocument(String url) {
        for (int retry = 0; retry <= MAX_RETRY; retry++) {
            try {
                return Jsoup.connect(url)
                        .userAgent(USER_AGENTS.get(random.nextInt(USER_AGENTS.size())))
                        .timeout(CONNECT_TIMEOUT)
                        .maxBodySize(0)
                        .followRedirects(true)
                        .get();
            } catch (IOException e) {
                log.warn("页面获取失败(第{}次): {} - {}", retry + 1, url, e.getMessage());
                if (retry < MAX_RETRY) {
                    try { Thread.sleep(2000L * (retry + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            }
        }
        return null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
