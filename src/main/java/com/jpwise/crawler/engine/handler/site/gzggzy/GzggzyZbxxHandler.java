package com.jpwise.crawler.engine.handler.site.gzggzy;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广州公共资源交易中心 - 中标信息处理器
 * 站点代码: gzggzy-slsw
 * 分类代码: zbxx
 * 
 * 特点：
 * - 详情页是表格格式 table.public-table.form-table
 * - 需要提取：项目名称、标段名称、中标人、中标价、招标人、中标日期
 */
@Slf4j
@Component
public class GzggzyZbxxHandler implements CrawlHandler {

    private static final String SITE_CODE = "gzggzy-slsw";
    private static final String CATEGORY_CODE = "zbxx";

    @Autowired
    private com.jpwise.crawler.engine.handler.PlaywrightCrawlHandler playwrightHandler;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        return SITE_CODE.equals(siteCode) && CATEGORY_CODE.equals(categoryCode);
    }

    @Override
    public String getHandlerName() {
        return "广州交易中心-中标信息处理器";
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getUrl(), maxPages);
        
        List<CrawlItem> items = playwrightHandler.crawlList(site, category, maxPages, reqInterval);
        
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());
        
        // 中标信息使用 HTML (Jsoup) 方式获取详情，保留 HTML 结构以便提取表格
        crawlDetailByHtml(item, site, reqInterval);
        
        log.debug("[{}] 详情获取完成: 项目={}, 标段={}, 中标人={}, 中标价={}", 
            getHandlerName(), 
            item.getProjectName(),
            item.getSectionName(),
            item.getBidWinner(),
            item.getBidAmount());
    }
    
    /**
     * 使用 HTML (Jsoup) 方式获取详情，并使用重构前的完整提取逻辑
     */
    private void crawlDetailByHtml(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
            return;
        }
        
        String detailUrl = item.getSourceUrl();
        if (!detailUrl.startsWith("http")) {
            detailUrl = site.getBaseUrl() + (detailUrl.startsWith("/") ? detailUrl : "/" + detailUrl);
        }
        
        try {
            if (reqInterval > 0) {
                Thread.sleep(reqInterval * 1000L);
            }
            
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .maxBodySize(0)
                    .followRedirects(true)
                    .get();
            
            org.jsoup.nodes.Element contentEl = doc.selectFirst(site.getDetailSelectors().getContent());
            if (contentEl != null) {
                item.setDetail(contentEl.text());
                item.setSummary(contentEl.text().length() > 500 ? contentEl.text().substring(0, 500) + "..." : contentEl.text());
                // 使用重构前的完整提取逻辑
                extractStructuredFromTable(contentEl, item);
            } else {
                // 备选：查找表格容器
                org.jsoup.nodes.Element tableContainer = doc.selectFirst("table.public-table, table.form-table, div.portlet");
                if (tableContainer != null) {
                    item.setDetail(tableContainer.text());
                    item.setSummary(tableContainer.text().length() > 500 ? tableContainer.text().substring(0, 500) + "..." : tableContainer.text());
                    extractStructuredFromTable(tableContainer, item);
                } else {
                    item.setDetail(doc.body().text());
                    item.setSummary(doc.body().text().length() > 500 ? doc.body().text().substring(0, 500) + "..." : doc.body().text());
                    extractStructuredFromTable(doc.body(), item);
                }
            }
            
            // 提取发布时间
            if (site.getDetailSelectors().getPublishTime() != null) {
                org.jsoup.nodes.Element timeEl = doc.selectFirst(site.getDetailSelectors().getPublishTime());
                if (timeEl != null) {
                    String timeText;
                    if (site.getDetailSelectors().getPublishTimeAttr() != null && 
                        site.getDetailSelectors().getPublishTimeAttr().startsWith("attr:")) {
                        String attrName = site.getDetailSelectors().getPublishTimeAttr().substring(5);
                        timeText = timeEl.attr(attrName);
                    } else {
                        timeText = timeEl.text();
                    }
                    if (timeText != null && !timeText.isBlank()) {
                        item.setPublishTime(timeText.length() > 19 ? timeText.substring(0, 19) : timeText.trim());
                    }
                }
            }
            
            item.setSourceUrl(detailUrl);
            log.debug("[{}] HTML 详情获取成功", getHandlerName());
            
        } catch (Exception e) {
            log.error("[{}] HTML 详情获取失败: {} - {}", getHandlerName(), detailUrl, e.getMessage());
        }
    }
    
    /**
     * 从 HTML 表格中提取结构化字段（重构前的完整逻辑）
     */
    private void extractStructuredFromTable(org.jsoup.nodes.Element contentEl, CrawlItem item) {
        // 查找所有表格（包括 template/shadow DOM 中的内容）
        org.jsoup.select.Elements tables = contentEl.select("table");
        if (tables.isEmpty()) {
            log.debug("[{}] 未找到表格元素", getHandlerName());
            return;
        }
        log.debug("[{}] 找到 {} 个表格", getHandlerName(), tables.size());

        // 构建标签→值的映射
        java.util.Map<String, String> kvMap = new java.util.LinkedHashMap<>();
        for (org.jsoup.nodes.Element table : tables) {
            org.jsoup.select.Elements rows = table.select("tr");
            for (int i = 0; i < rows.size(); i++) {
                org.jsoup.nodes.Element row = rows.get(i);
                org.jsoup.select.Elements cells = row.select("td, th");
                
                if (cells.size() >= 2) {
                    // 检查是否是标签行（全是tdTitle类）
                    boolean isAllTitleCells = true;
                    for (org.jsoup.nodes.Element cell : cells) {
                        if (!cell.hasClass("tdTitle") && !cell.hasClass("title")) {
                            isAllTitleCells = false;
                            break;
                        }
                    }
                    
                    // 如果是标签行，且下一行有相同数量的单元格，则配对提取
                    if (isAllTitleCells && i + 1 < rows.size()) {
                        org.jsoup.select.Elements nextRowCells = rows.get(i + 1).select("td, th");
                        if (nextRowCells.size() == cells.size()) {
                            for (int j = 0; j < cells.size(); j++) {
                                String label = cells.get(j).text().trim();
                                String value = nextRowCells.get(j).text().trim();
                                if (!label.isBlank() && !value.isBlank()) {
                                    kvMap.put(label, value);
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
                    }
                    // 4列格式：标签1|值1|标签2|值2
                    if (cells.size() >= 4) {
                        String label2 = cells.get(2).text().trim();
                        String value2 = cells.get(3).text().trim();
                        if (!label2.isBlank() && !value2.isBlank()) {
                            kvMap.put(label2, value2);
                        }
                    }
                }
            }
        }

        if (kvMap.isEmpty()) {
            return;
        }
        log.info("[{}] 从表格提取到 {} 个键值对", getHandlerName(), kvMap.size());

        // 按标签名映射到 CrawlItem 字段（重构前的完整映射逻辑）
        // 注意：需要优先处理招标项目名称，其次才是投资项目名称
        String projectName = null;
        String sectionName = null;
        String tenderer = null;
        
        // 第一遍：提取所有关键字段
        for (java.util.Map.Entry<String, String> entry : kvMap.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue();
            
            if (label.contains("招标项目名称")) {
                projectName = value;
            } else if (label.contains("投资项目名称") && projectName == null) {
                projectName = value;
            } else if (label.contains("项目名称") && projectName == null) {
                projectName = value;
            } else if ((label.contains("标段") || label.contains("包"))) {
                sectionName = value;
            } else if (label.contains("招标单位")) {
                tenderer = value;
            }
        }
        
        // 设置项目名称（优先使用招标项目名称）
        if (projectName != null) {
            item.setProjectName(projectName);
        }
        
        // 设置标段名称
        if (sectionName != null) {
            item.setSectionName(sectionName);
        }
        
        // 设置招标人（使用招标单位的值）
        if (tenderer != null) {
            item.setTenderer(tenderer);
        }
        
        // 第二遍：处理其他字段
        for (java.util.Map.Entry<String, String> entry : kvMap.entrySet()) {
            String label = entry.getKey();
            String value = entry.getValue();

            if (label.contains("最高投标限价") || label.contains("最高限价") || label.contains("控制价")) {
                if (item.getMaxPrice() == null) item.setMaxPrice(value);
            } else if (label.contains("开标时间")) {
                if (item.getOpenTime() == null) item.setOpenTime(value);
            } else if (label.contains("开标地点")) {
                if (item.getOpenPlace() == null) item.setOpenPlace(value);
            } else if (label.contains("联系人") && item.getTenderContact() == null) {
                item.setTenderContact(value);
            } else if ((label.contains("联系电话") || label.contains("电话")) && item.getTenderTel() == null) {
                item.setTenderTel(value);
            } else if ((label.contains("中标人") || label.contains("中标单位")) &&
                     (item.getBidWinner() == null || item.getBidWinner().isBlank())) {
                item.setBidWinner(value);
            } else if ((label.contains("中标价") || label.contains("中标金额")) &&
                     (item.getBidAmount() == null || item.getBidAmount().isBlank())) {
                // 提取数字部分
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\d,]+\\.?\\d*");
                java.util.regex.Matcher matcher = pattern.matcher(value.replace(",", ""));
                if (matcher.find()) {
                    item.setBidAmount(matcher.group().replace(",", ""));
                }
            } else if (label.contains("中标日期") &&
                     (item.getBidDate() == null || item.getBidDate().isBlank())) {
                item.setBidDate(normalizeDateTime(value));
            }
        }
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
}
