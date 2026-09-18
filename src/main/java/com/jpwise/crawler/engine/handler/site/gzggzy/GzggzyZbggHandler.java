package com.jpwise.crawler.engine.handler.site.gzggzy;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 广州公共资源交易中心 - 招标公告处理器
 * 站点代码: gzggzy-slsw
 * 分类代码: zbgg
 * 
 * 特点：
 * - 使用 Playwright 渲染（JS动态加载）
 * - 列表页格式：table.public-table tr
 * - 详情页有结构化表格
 */
@Slf4j
@Component
public class GzggzyZbggHandler implements CrawlHandler {

    private static final String SITE_CODE = "gzggzy-slsw";
    private static final String CATEGORY_CODE = "zbgg";

    @Autowired
    private com.jpwise.crawler.engine.handler.PlaywrightCrawlHandler playwrightHandler;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        return SITE_CODE.equals(siteCode) && CATEGORY_CODE.equals(categoryCode);
    }

    @Override
    public String getHandlerName() {
        return "广州交易中心-招标公告处理器";
    }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getUrl(), maxPages);
        
        // 使用 Playwright 爬取列表
        List<CrawlItem> items = playwrightHandler.crawlList(site, category, maxPages, reqInterval);
        
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());
        
        // 招标公告使用 HTML (Jsoup) 方式获取详情，保留 HTML 结构以便扫描块级元素
        crawlDetailByHtml(item, site, reqInterval);
        
        // 然后使用广州站点特定的提取逻辑
        extractGzggzyZbggDetail(item);
        
        log.debug("[{}] 详情获取完成: 招标人={}", getHandlerName(), item.getTenderer());
    }
    
    /**
     * 使用 HTML (Jsoup) 方式获取详情，保留 HTML 结构
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
                // 保留原始 HTML 内容，不要只取 text()
                item.setDetail(contentEl.html());
                item.setSummary(contentEl.text().length() > 500 ? contentEl.text().substring(0, 500) + "..." : contentEl.text());
                log.debug("[{}] HTML 详情获取成功，长度: {}", getHandlerName(), contentEl.html().length());
            } else {
                // 如果找不到内容区域，使用 body
                item.setDetail(doc.body().html());
                item.setSummary(doc.body().text().length() > 500 ? doc.body().text().substring(0, 500) + "..." : doc.body().text());
                log.debug("[{}] HTML 详情获取成功(使用 body)，长度: {}", getHandlerName(), doc.body().html().length());
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
            
        } catch (Exception e) {
            log.error("[{}] HTML 详情获取失败: {} - {}", getHandlerName(), detailUrl, e.getMessage());
        }
    }

    /**
     * 广州招标公告详情页特定提取逻辑
     * 从正文中提取招标人、项目名称等字段
     * 使用重构前的完整逻辑：标签匹配 + 落款匹配（从块级元素扫描）
     */
    private void extractGzggzyZbggDetail(CrawlItem item) {
        if (item.getDetail() == null || item.getDetail().isBlank()) {
            return;
        }
        
        // 使用 Jsoup 解析 HTML（item.getDetail() 现在是 HTML）
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(item.getDetail());
        org.jsoup.nodes.Element contentMain = doc.selectFirst("div.detail-content-main");
        if (contentMain == null) {
            contentMain = doc.body(); // 如果找不到，使用 body
        }
        
        String fullText = contentMain.text();
        log.debug("[{}] 开始提取，正文长度: {}", getHandlerName(), fullText.length());
        
        // ---- 策略1：标签匹配（"招标人：XXX"、"招标单位：XXX"等） ----
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
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
                            log.info("[{}] 从标签提取招标人: {}", getHandlerName(), val);
                            break;
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }
        
        // ---- 策略2：落款匹配（从末尾向前找单位名称） ----
        if (item.getTenderer() == null || item.getTenderer().isBlank()) {
            // 获取所有块级子元素（p, div, span 等）
            org.jsoup.select.Elements blocks = contentMain.select("p, div, span, td");
            log.debug("[{}] 策略2：扫描 {} 个块级元素", getHandlerName(), blocks.size());
            
            // 扩展组织后缀列表
            String orgSuffixes = "(?:中心|公司|局|处|院|所|厅|委|办|站|队|部|会|集团|总队|支队|大学|学院|学校|研究所|设计院|监理部|事务所|研究院|开发部|管理局|有限公司|分公司|子公司|营业部|项目部|工程部|指挥部|监督站)";
            
            for (int i = blocks.size() - 1; i >= Math.max(0, blocks.size() - 20); i--) {
                String text = blocks.get(i).text().trim();
                
                // 跳过空段、"特此公告"等
                if (text.isBlank() || text.length() < 4 || text.length() > 100) continue;
                if (text.contains("特此公告") || text.contains("特此通知")) continue;
                
                // 找到以组织后缀结尾的文本
                if (text.matches(".*" + orgSuffixes + "$")) {
                    item.setTenderer(text);
                    log.info("[{}] 从落款提取招标人: {}", getHandlerName(), text);
                    
                    // 同时尝试从相邻元素提取日期
                    if (item.getPublishTime() == null || item.getPublishTime().isBlank()) {
                        extractPublishDateFromAdjacentBlocks(blocks, i, item);
                    }
                    break;
                }
            }
        }
        
        // 3. 提取项目名称（优先招标项目名称）
        if (item.getProjectName() == null || item.getProjectName().isBlank()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "招标项目名称[：:\\s]+(.+?)(?=\\n|项目编号|$)")
                .matcher(fullText);
            if (matcher.find()) {
                item.setProjectName(matcher.group(1).trim());
                log.debug("[{}] 提取项目名称: {}", getHandlerName(), item.getProjectName());
            }
        }
    }
    
    /**
     * 从相邻块级元素中提取发布日期
     */
    private void extractPublishDateFromAdjacentBlocks(org.jsoup.select.Elements blocks, int tendererIndex, CrawlItem item) {
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
                log.debug("[{}] 从相邻落款提取日期: {}", getHandlerName(), formattedDate);
                return;
            }
        }
    }
}
