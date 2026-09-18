package com.jpwise.crawler.engine.handler.site.gzggzy;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 广州公共资源交易中心 - 中标结果公示处理器
 * 站点代码: gzggzy-slsw
 * 分类代码: zbjggs
 * 
 * 特点：
 * - 详情页是纯 HTML 文本格式（不是表格）
 * - 内容嵌套在 div.detail-content-main 中
 * - 需要提取：标段名称、中标人、中标价、招标人、中标日期
 */
@Slf4j
@Component
public class GzggzyZbjggsHandler implements CrawlHandler {

    private static final String SITE_CODE = "gzggzy-slsw";
    private static final String CATEGORY_CODE = "zbjggs";

    @Autowired
    private com.jpwise.crawler.engine.handler.PlaywrightCrawlHandler playwrightHandler;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        return SITE_CODE.equals(siteCode) && CATEGORY_CODE.equals(categoryCode);
    }

    @Override
    public String getHandlerName() {
        return "广州交易中心-中标结果公示处理器";
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
        
        // 使用 Playwright 获取页面内容
        playwrightHandler.crawlDetail(item, site, reqInterval);
        
        // 使用广州中标结果公示特定的提取逻辑
        extractGzggzyZbjggsDetail(item);
        
        log.debug("[{}] 详情获取完成: 标段={}, 中标人={}, 中标价={}, 招标人={}, 中标日期={}", 
            getHandlerName(), 
            item.getSectionName(),
            item.getBidWinner(),
            item.getBidAmount(),
            item.getTenderer(),
            item.getBidDate());
    }

    /**
     * 广州中标结果公示详情页特定提取逻辑
     * 处理纯 HTML 文本格式
     */
    private void extractGzggzyZbjggsDetail(CrawlItem item) {
        if (item.getDetail() == null || item.getDetail().isBlank()) {
            log.warn("[{}] 详情内容为空，无法提取", getHandlerName());
            return;
        }

        // 使用 Jsoup 解析 HTML
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(item.getDetail());
        String text = item.getDetail();
        
        // 1. 提取标段名称（优先）
        if (item.getSectionName() == null || item.getSectionName().isBlank()) {
            // 尝试从 <p>标段名称：XXX</p> 提取
            org.jsoup.select.Elements elements = doc.select("p:contains(标段名称)");
            if (!elements.isEmpty()) {
                String sectionName = elements.first().text();
                // 移除 "标段名称：" 前缀
                if (sectionName.contains("：")) {
                    sectionName = sectionName.substring(sectionName.indexOf("：") + 1).trim();
                } else if (sectionName.contains(":")) {
                    sectionName = sectionName.substring(sectionName.indexOf(":") + 1).trim();
                }
                item.setSectionName(sectionName);
                log.debug("[{}] 提取标段名称: {}", getHandlerName(), item.getSectionName());
            } else {
                // 正则备用方案
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "标段[\\(（]包[\\)）]?名称[：:\\s]+(.+?)(?=\\n|标段编号|$)")
                    .matcher(text);
                if (matcher.find()) {
                    item.setSectionName(matcher.group(1).trim());
                    log.debug("[{}] 提取标段名称(正则): {}", getHandlerName(), item.getSectionName());
                }
            }
        }
        
        // 2. 项目名称 = 标段名称（用户要求两者相同）
        if (item.getProjectName() == null || item.getProjectName().isBlank()) {
            if (item.getSectionName() != null && !item.getSectionName().isBlank()) {
                item.setProjectName(item.getSectionName());
                log.debug("[{}] 设置项目名称(等于标段名称): {}", getHandlerName(), item.getProjectName());
            } else {
                // 备选：从标题提取
                if (item.getTitle() != null && !item.getTitle().isBlank()) {
                    String title = item.getTitle();
                    // 移除常见的后缀
                    String[] suffixes = {"中标结果公示", "中标候选人公示", "招标公告", "中标公示"};
                    for (String suffix : suffixes) {
                        int idx = title.indexOf(suffix);
                        if (idx > 0) {
                            title = title.substring(0, idx).trim();
                            break;
                        }
                    }
                    if (!title.isBlank()) {
                        item.setProjectName(title);
                        log.debug("[{}] 从标题提取项目名称: {}", getHandlerName(), item.getProjectName());
                    }
                }
            }
        }

        // 3. 提取中标人（从文本或表格中提取）
        if (item.getBidWinner() == null || item.getBidWinner().isBlank()) {
            // 方法1：从纯文本中提取
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "中标人[：:\\s]*([^\\n，。]+?)(?=\\n|中标价|$)")
                .matcher(text);
            if (matcher.find()) {
                String winner = matcher.group(1).trim().replaceAll("<[^>]+>", "");
                if (!winner.isBlank() && winner.length() > 2) {
                    item.setBidWinner(winner);
                    log.debug("[{}] 提取中标人: {}", getHandlerName(), item.getBidWinner());
                }
            }
            
            // 方法2：从表格中提取（查找包含"中标人"或"中标单位"的单元格）
            if (item.getBidWinner() == null || item.getBidWinner().isBlank()) {
                org.jsoup.select.Elements tables = doc.select("table");
                for (org.jsoup.nodes.Element table : tables) {
                    org.jsoup.select.Elements rows = table.select("tr");
                    for (org.jsoup.nodes.Element row : rows) {
                        org.jsoup.select.Elements cells = row.select("td, th");
                        for (org.jsoup.nodes.Element cell : cells) {
                            String cellText = cell.text();
                            // 如果单元格内容包含中标单位/中标人，且不是标题行
                            if ((cellText.contains("中标单位") || cellText.contains("中标人")) 
                                && !cellText.equals("中标单位") && !cellText.equals("中标人")) {
                                // 提取中标人名称（移除标签）
                                String winner = cellText.replaceAll("中标人[：:\\s]*", "")
                                                        .replaceAll("中标单位[：:\\s]*", "")
                                                        .trim();
                                if (!winner.isBlank() && winner.length() > 2) {
                                    item.setBidWinner(winner);
                                    log.debug("[{}] 提取中标人(表格): {}", getHandlerName(), item.getBidWinner());
                                    break;
                                }
                            }
                        }
                        if (item.getBidWinner() != null && !item.getBidWinner().isBlank()) break;
                    }
                    if (item.getBidWinner() != null && !item.getBidWinner().isBlank()) break;
                }
            }
        }

        // 4. 提取中标价（从文本或表格中提取）
        if (item.getBidAmount() == null || item.getBidAmount().isBlank()) {
            // 方法1：从纯文本中提取（支持"中标价（万元）："格式）
            // 先尝试匹配"中标价（万元）："格式
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "中标价[（(]万元[）)][：:\\s]*([\\d,\\.]+)")
                .matcher(text);
            if (matcher.find()) {
                item.setBidAmount(matcher.group(1).replace(",", ""));
                log.debug("[{}] 提取中标价(万元): {}", getHandlerName(), item.getBidAmount());
            } else {
                // 再尝试普通"中标价："格式
                matcher = java.util.regex.Pattern.compile(
                    "中标价[：:\\s]*([\\d,\\.]+)")
                    .matcher(text);
                if (matcher.find()) {
                    item.setBidAmount(matcher.group(1).replace(",", ""));
                    log.debug("[{}] 提取中标价: {}", getHandlerName(), item.getBidAmount());
                }
            }
            
            // 方法2：从表格中提取（查找包含"中标价"或"中标价（万元）"的单元格）
            if (item.getBidAmount() == null || item.getBidAmount().isBlank()) {
                org.jsoup.select.Elements tables = doc.select("table");
                for (org.jsoup.nodes.Element table : tables) {
                    org.jsoup.select.Elements rows = table.select("tr");
                    for (org.jsoup.nodes.Element row : rows) {
                        org.jsoup.select.Elements cells = row.select("td, th");
                        for (org.jsoup.nodes.Element cell : cells) {
                            String cellText = cell.text();
                            // 如果单元格内容包含中标价（万元），且不是标题行
                            if ((cellText.contains("中标价") || cellText.contains("中标价（万元）")) 
                                && !cellText.equals("中标价") && !cellText.equals("中标价（万元）")) {
                                // 提取数字
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                    "([\\d,\\.]+)")
                                    .matcher(cellText);
                                if (m.find()) {
                                    item.setBidAmount(m.group(1).replace(",", ""));
                                    log.debug("[{}] 提取中标价(表格): {}", getHandlerName(), item.getBidAmount());
                                    break;
                                }
                            }
                        }
                        if (item.getBidAmount() != null && !item.getBidAmount().isBlank()) break;
                    }
                    if (item.getBidAmount() != null && !item.getBidAmount().isBlank()) break;
                }
            }
        }

        // 5. 提取招标人（从纯文本中提取，包括落款格式）
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
            // 方法1：从 "招标人名称：" 提取
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "招标人[名称]*[：:\\s]+([^\\n，。（(]{2,50}?)(?=[\\n，。（(]|$)")
                .matcher(text);
            if (matcher.find()) {
                item.setTenderer(matcher.group(1).trim());
                log.debug("[{}] 提取招标人: {}", getHandlerName(), item.getTenderer());
            } else {
                // 方法2：从落款处提取（扫描最后几行以公司/中心/集团等结尾的单位名称）
                org.jsoup.select.Elements blocks = doc.select("p, div");
                String orgSuffixes = "(?:中心|公司|局|集团|院|所|部|会|大学|学院|学校)";
                
                // 从后向前扫描
                for (int i = blocks.size() - 1; i >= Math.max(0, blocks.size() - 10); i--) {
                    String blockText = blocks.get(i).text().trim();
                    
                    // 跳过空行和日期行
                    if (blockText.isBlank() || blockText.length() < 4 || blockText.length() > 50) continue;
                    if (blockText.matches("^\\d{4}年\\d{1,2}月\\d{1,2}日$")) continue;
                    if (blockText.contains("日期：")) continue;
                    
                    // 找到以组织后缀结尾的文本（如"广州高新建设开发集团有限公司"）
                    if (blockText.matches(".*" + orgSuffixes + "$")) {
                        item.setTenderer(blockText);
                        log.debug("[{}] 从落款提取招标人: {}", getHandlerName(), item.getTenderer());
                        break;
                    }
                }
            }
        }
        
        // 6. 标段名称 = 项目名称（如果标段名称为空但项目名称有值）
        if ((item.getSectionName() == null || item.getSectionName().isBlank()) 
            && item.getProjectName() != null && !item.getProjectName().isBlank()) {
            item.setSectionName(item.getProjectName());
            log.debug("[{}] 设置标段名称(等于项目名称): {}", getHandlerName(), item.getSectionName());
        }

        // 6. 提取中标日期（优先定标时间，其次日期）
        if (item.getBidDate() == null || item.getBidDate().isBlank()) {
            // 先尝试定标时间
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "定标时间[：:\\s]*(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
                .matcher(text);
            if (matcher.find()) {
                item.setBidDate(String.format("%s-%02d-%02d", 
                    matcher.group(1), 
                    Integer.parseInt(matcher.group(2)), 
                    Integer.parseInt(matcher.group(3))));
                log.debug("[{}] 提取中标日期(定标时间): {}", getHandlerName(), item.getBidDate());
            } else {
                // 再尝试日期（但要排除"公示时间"）
                matcher = java.util.regex.Pattern.compile(
                    "(?<!公示)日期[：:\\s]*(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
                    .matcher(text);
                if (matcher.find()) {
                    item.setBidDate(String.format("%s-%02d-%02d", 
                        matcher.group(1), 
                        Integer.parseInt(matcher.group(2)), 
                        Integer.parseInt(matcher.group(3))));
                    log.debug("[{}] 提取中标日期: {}", getHandlerName(), item.getBidDate());
                }
            }
        }
    }
}
