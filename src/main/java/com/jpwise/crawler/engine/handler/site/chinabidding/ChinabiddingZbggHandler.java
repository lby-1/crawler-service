package com.jpwise.crawler.engine.handler.site.chinabidding;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.engine.PlaywrightCrawlEngine;
import com.jpwise.crawler.engine.handler.CrawlHandler;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国采购与招标网 - 招标公告处理器
 * 列表：Playwright引擎（WAF保护，需headless渲染）
 * 详情：Playwright引擎（同样WAF保护）
 * 正文格式：纯文本段落，含"招标人"、"项目名称"、"联系电话"等
 * 特点：省份信息从列表页的第4列提取，城市从正文提取
 */
@Slf4j
@Component
public class ChinabiddingZbggHandler implements CrawlHandler {

    private static final String[] SITE_CODES = {"chinabidding", "chinabidding-sl"};
    private static final String CATEGORY_CODE = "sl-zbxx";

    @Autowired
    private PlaywrightCrawlEngine playwrightCrawlEngine;

    @Override
    public boolean supports(String siteCode, String categoryCode) {
        for (String code : SITE_CODES) {
            if (code.equals(siteCode)) return CATEGORY_CODE.equals(categoryCode);
        }
        return false;
    }

    @Override
    public String getHandlerName() { return "中采网-水利招标公告处理器"; }

    @Override
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                      CrawlerProperties.CategoryConfig category,
                                      int maxPages, int reqInterval) {
        log.info("[{}] 开始爬取列表: {} - 最大{}页", getHandlerName(), category.getName(), maxPages);
        List<CrawlItem> items = playwrightCrawlEngine.crawlList(site, category, maxPages, reqInterval);
        log.info("[{}] 列表爬取完成，共 {} 条", getHandlerName(), items.size());
        return items;
    }

    @Override
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        log.debug("[{}] 获取详情: {}", getHandlerName(), item.getTitle());

        // 使用 Playwright 获取详情（WAF保护）
        playwrightCrawlEngine.crawlDetail(item, site, reqInterval);

        // 从已获取的 detail 文本中提取结构化字段
        String detail = item.getDetail();
        if (detail != null && !detail.isBlank()) {
            extractFields(item, detail);
        }

        log.info("[{}] 详情完成: 项目={}, 招标人={}, 限价={}, 省份={}, 城市={}",
            getHandlerName(), item.getProjectName(), item.getTenderer(), item.getMaxPrice(),
            item.getProvince(), item.getCity());
    }

    /**
     * 从详情纯文本提取结构化字段
     * 中采网详情页格式多样，以正则为主
     */
    private void extractFields(CrawlItem item, String text) {
        // 项目名称
        if (item.getProjectName() == null) {
            String val = regexFirst(text, "(?:招标项目名称|项目名称|工程名称)[：:]+\\s*(.+?)(?=[\\n，。]|招标编号|项目编号|$)");
            if (val != null && val.length() >= 4) item.setProjectName(val);
        }
        // 项目名称 fallback：从标题提取
        if (item.getProjectName() == null && item.getTitle() != null) {
            String title = item.getTitle().replaceAll("招标(公告|信息)$", "").trim();
            if (!title.isBlank()) item.setProjectName(title);
        }

        // 招标人
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "(?:招标人|招标单位|建设单位|采购人|业主)[（(全称)）]*[为是：:]+\\s*([^\\n，。,;；]{2,50}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }

        // 标段名称
        if (item.getSectionName() == null) {
            String val = regexFirst(text, "标段[（(包)）]*名称[：:]+\\s*(.+?)(?=[\\n，。]|$)");
            if (val != null && val.length() >= 2) item.setSectionName(val);
        }

        // 最高投标限价（中采网金额可能是元或万元）
        if (item.getMaxPrice() == null) {
            String val = regexFirst(text, "(?:最高投标限价|招标控制价|最高限价|控制价)[^\\d]*([\\d,\\.]+\\s*(?:万元|亿元|元)?)");
            if (val != null) item.setMaxPrice(normalizePrice(val));
        }

        // 开标时间
        if (item.getOpenTime() == null) {
            String val = regexFirst(text, "(?:开标时间|投标截止时间|递交投标文件截止时间)[：:]*\\s*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[时:：]\\d{1,2}(?:[分:：]\\d{1,2})?)?)");
            if (val != null) item.setOpenTime(normalizeDateTime(val));
        }

        // 开标地点
        if (item.getOpenPlace() == null) {
            String val = regexFirst(text, "(?:开标地点|开标地址|建设地点)[：:]+\\s*(.+?)(?=[\\n。，]|$)");
            if (val != null && val.length() >= 4) item.setOpenPlace(val);
        }

        // 联系人
        if (item.getTenderContact() == null) {
            String val = regexFirst(text, "(?:招标人)?联\\s*系\\s*人[：:]+\\s*([^\\n，。电话]{2,20}?)(?=[\\s电话，。\\n]|$)");
            if (val != null) item.setTenderContact(val);
        }

        // 联系电话
        if (item.getTenderTel() == null) {
            String val = regexFirst(text, "(?:联系)?电\\s*话[：:]+\\s*([\\d\\-()（）]{7,20})");
            if (val != null) item.setTenderTel(val);
        }

        // 城市：从正文中提取（中采网是全国聚合站，省份从列表页已取）
        if (item.getCity() == null || item.getCity().isBlank()) {
            // 从建设地点提取
            if (item.getOpenPlace() != null) {
                String city = regexFirst(item.getOpenPlace(), "([\\u4e00-\\u9fa5]{2,6}(?:市|县|州|地区|盟))");
                if (city != null) item.setCity(city);
            }
            // 从招标人名称提取
            if (item.getCity() == null && item.getTenderer() != null) {
                String city = regexFirst(item.getTenderer(), "^([\\u4e00-\\u9fa5]{2,6}(?:市|县|州|地区|盟))");
                if (city != null) item.setCity(city);
            }
        }

        // 中标人（部分页面可能是中标公示混在一起）
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "(?:中标人|中标单位|中标供应商)[：:]+\\s*([^\\n，。,;；]{2,80}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setBidWinner(val);
        }

        // 中标价
        if (item.getBidAmount() == null) {
            String val = regexFirst(text, "(?:中标价|中标金额|成交价)[格额]?[：:]+\\s*([\\d,\\.]+)\\s*(?:万元|亿元|元)?");
            if (val != null) item.setBidAmount(val.replace(",", ""));
        }
    }

    private String regexFirst(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String val = m.group(1).trim();
                return val.isBlank() ? null : val;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private String normalizeDateTime(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim();
        Matcher dm = Pattern.compile("(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").matcher(s);
        if (!dm.find()) return s;
        String datePart = dm.group(1) + "-" + String.format("%02d", Integer.parseInt(dm.group(2)))
                + "-" + String.format("%02d", Integer.parseInt(dm.group(3)));
        String remaining = s.substring(dm.end()).trim();
        Matcher tm = Pattern.compile("(\\d{1,2})[时:：](\\d{1,2})(?:[分:：](\\d{1,2})秒?)?").matcher(remaining);
        if (tm.find()) {
            return datePart + " " + String.format("%02d", Integer.parseInt(tm.group(1)))
                    + ":" + String.format("%02d", Integer.parseInt(tm.group(2)))
                    + ":" + (tm.group(3) != null ? String.format("%02d", Integer.parseInt(tm.group(3))) : "00");
        }
        return datePart;
    }

    private String normalizePrice(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim().replace(",", "");
        Matcher m = Pattern.compile("([\\d.]+)\\s*(万元|亿元|元)?").matcher(s);
        if (!m.find()) return s;
        String numStr = m.group(1);
        String unit = m.group(2);
        try {
            double num = Double.parseDouble(numStr);
            if ("元".equals(unit)) return String.format("%.2f", num / 10000.0);
            if ("亿元".equals(unit)) return String.format("%.2f", num * 10000.0);
            return numStr;
        } catch (NumberFormatException e) { return s; }
    }
}
