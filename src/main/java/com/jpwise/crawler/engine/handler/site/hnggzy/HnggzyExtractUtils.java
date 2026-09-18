package com.jpwise.crawler.engine.handler.site.hnggzy;

import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海南公共资源交易中心 - 字段提取工具类
 * 海南详情页特点：
 *   1. 正文在 div.contnet_con（注意拼写）
 *   2. 招标公告：纯文本段落格式，招标人/建设地点/最高限价/投标截止时间等在正文中
 *   3. 中标结果：纯文本，中标人/中标价/招标人/联系人/电话在正文中
 *   4. 部分页面含表格
 */
@Slf4j
public class HnggzyExtractUtils {

    /**
     * 从 HTML 表格中提取字段
     */
    public static void extractFromTable(CrawlItem item, Element contentEl) {
        Elements tables = contentEl.select("table");
        for (Element table : tables) {
            for (Element row : table.select("tr")) {
                Elements cells = row.select("td, th");
                if (cells.size() >= 2) {
                    String label = cells.get(0).text().trim().replaceAll("[：:]+$", "").trim();
                    String value = cells.get(1).text().trim();
                    if (label.isBlank() || value.isBlank()) continue;
                    matchField(item, label, value);
                    if (cells.size() >= 4) {
                        String label2 = cells.get(2).text().trim().replaceAll("[：:]+$", "").trim();
                        String value2 = cells.get(3).text().trim();
                        if (!label2.isBlank() && !value2.isBlank()) matchField(item, label2, value2);
                    }
                }
            }
        }
    }

    /**
     * 从纯文本提取招标公告字段
     */
    public static void extractZbggFields(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 项目名称：从标题或正文提取
        if (item.getProjectName() == null) {
            String val = regexFirst(text, "(?:招标项目名称|项目名称)[：:]+\\s*(.+?)(?=[，。\\n]|招标项目编号|$)");
            if (val != null && val.length() >= 4) item.setProjectName(val);
        }

        // 招标人
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "招标人[（(项目业主)）]*[为是：:]+\\s*([^，。,;；\\n]{2,50}?)(?=[，。,;；\\s\\n]|$)");
            if (val != null) item.setTenderer(val);
        }

        // 建设地点 → openPlace
        if (item.getOpenPlace() == null) {
            String val = regexFirst(text, "建设地点[：:]+\\s*(.+?)(?=[\\n]|\\d\\.|[一二三四五六七八九十]|$)");
            if (val != null && val.length() >= 2) item.setOpenPlace(val);
        }

        // 城市：从建设地点提取
        if ((item.getCity() == null || item.getCity().isBlank()) && item.getOpenPlace() != null) {
            String city = regexFirst(item.getOpenPlace(), "([\\u4e00-\\u9fa5]{2,6}(?:市|县|州|地区))");
            if (city != null) item.setCity(city);
        }
        // 城市 fallback：从招标人名称提取
        if ((item.getCity() == null || item.getCity().isBlank()) && item.getTenderer() != null) {
            String city = regexFirst(item.getTenderer(), "^([\\u4e00-\\u9fa5]{2,6}(?:市|县|州|地区))");
            if (city != null) item.setCity(city);
        }

        // 最高投标限价（海南格式："最高投标限价（或招标控制价): 7449339.17"，单位元）
        if (item.getMaxPrice() == null) {
            String val = regexFirst(text, "(?:最高投标限价|招标控制价|最高限价)[^\\d]*([\\d,]+\\.?\\d*)");
            if (val != null) {
                item.setMaxPrice(normalizePrice(val.replace(",", "") + "元"));
            }
        }

        // 投标截止时间 → openTime
        if (item.getOpenTime() == null) {
            String val = regexFirst(text, "(?:投标文件递交的截止时间|投标截止时间)[^为]*为\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日\\d{1,2}时\\d{1,2}分)");
            if (val != null) item.setOpenTime(normalizeDateTime(val));
        }

        // 开标地点
        if (item.getOpenPlace() == null || item.getOpenPlace().length() < 4) {
            String val = regexFirst(text, "地点为[：:]*\\s*(.+?)(?=[。（(]|适用于|$)");
            if (val != null && val.length() >= 4) item.setOpenPlace(val);
        }

        // 联系人
        if (item.getTenderContact() == null) {
            // 招标人联系人优先
            String val = regexFirst(text, "招\\s*标\\s*人[：:].*?联\\s*系\\s*人[：:]+\\s*([^\\n，。电]{2,20}?)(?=[\\s电，。\\n]|$)");
            if (val == null) {
                val = regexFirst(text, "联\\s*系\\s*人[（(全称)）]*[：:]+\\s*([^\\n，。电]{2,20}?)(?=[\\s电，。\\n]|$)");
            }
            if (val != null) item.setTenderContact(val);
        }

        // 联系电话
        if (item.getTenderTel() == null) {
            // 招标人电话优先
            String val = regexFirst(text, "招\\s*标\\s*人[：:].*?电\\s*话[：:]+\\s*([\\d\\-()（）]{7,20})");
            if (val == null) {
                val = regexFirst(text, "电\\s*话[：:]+\\s*([\\d\\-()（）]{7,20})");
            }
            if (val != null) item.setTenderTel(val);
        }

        // 标段名称
        if (item.getSectionName() == null) {
            String val = regexFirst(text, "标段[（(包)）]*名称[：:]+\\s*(.+?)(?=[，。\\n]|$)");
            if (val != null && val.length() >= 2) item.setSectionName(val);
        }
    }

    /**
     * 从纯文本提取中标结果字段
     */
    public static void extractZbjgFields(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 中标人
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "中标人[：:]+\\s*([^\\n，。,;；]{2,80}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setBidWinner(val);
        }

        // 中标价格/金额
        if (item.getBidAmount() == null) {
            String val = regexFirst(text, "(?:中标价格|中标价|中标金额|投标报价)[：:]+\\s*([\\d,\\.]+)");
            if (val != null) item.setBidAmount(val.replace(",", ""));
        }

        // 招标人
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "招\\s*标\\s*人[：:]+\\s*([^\\n，。地址]{2,50}?)(?=[\\s，。\\n地址]|$)");
            if (val != null) item.setTenderer(val);
        }

        // 联系人
        if (item.getTenderContact() == null) {
            // 取"招标人"后面第一个联系人
            String val = regexFirst(text, "招\\s*标\\s*人[：:].*?联\\s*系\\s*人[：:]+\\s*([^\\n，。电]{2,20}?)(?=[\\s电，。\\n]|$)");
            if (val != null) item.setTenderContact(val);
        }

        // 联系电话
        if (item.getTenderTel() == null) {
            String val = regexFirst(text, "招\\s*标\\s*人[：:].*?电\\s*话[：:]+\\s*([\\d\\-()（）]{7,20})");
            if (val != null) item.setTenderTel(val);
        }

        // 城市
        if ((item.getCity() == null || item.getCity().isBlank()) && item.getTenderer() != null) {
            String city = regexFirst(item.getTenderer(), "^([\\u4e00-\\u9fa5]{2,6}(?:市|县|州|地区))");
            if (city != null) item.setCity(city);
        }

        // 项目名称 fallback
        if (item.getProjectName() == null && item.getTitle() != null) {
            String title = item.getTitle();
            for (String suffix : List.of("中标结果公告", "中标候选人公示", "中标公示", "中标公告")) {
                int idx = title.indexOf(suffix);
                if (idx > 0) { title = title.substring(0, idx).trim(); break; }
            }
            // 去掉"（机器管招投标）"前缀
            title = title.replaceAll("^[（(]机器管招投标[）)]", "").trim();
            if (!title.isBlank()) item.setProjectName(title);
        }
    }

    // ---- private helpers ----

    private static final java.util.List<String> EMPTY = java.util.Collections.emptyList();

    private static void matchField(CrawlItem item, String label, String value) {
        if (label.contains("项目名称") && item.getProjectName() == null) {
            item.setProjectName(value);
        } else if (label.contains("标段") && label.contains("名称") && item.getSectionName() == null) {
            item.setSectionName(value);
        } else if ((label.contains("最高") || label.contains("控制价") || label.contains("限价")) && item.getMaxPrice() == null) {
            item.setMaxPrice(normalizePrice(value));
        } else if ((label.contains("建设地点") || label.contains("开标地点")) && item.getOpenPlace() == null) {
            item.setOpenPlace(value);
        } else if ((label.contains("投标截止") || label.contains("开标时间")) && item.getOpenTime() == null) {
            item.setOpenTime(normalizeDateTime(value));
        } else if (label.contains("招标人") && !label.contains("代理") && !label.contains("联系") && !label.contains("电话") && item.getTenderer() == null) {
            item.setTenderer(value);
        } else if (label.contains("联系人") && item.getTenderContact() == null) {
            item.setTenderContact(value);
        } else if (label.contains("电话") && item.getTenderTel() == null) {
            item.setTenderTel(value);
        } else if ((label.contains("中标人") || label.contains("中标单位")) && item.getBidWinner() == null) {
            item.setBidWinner(value);
        } else if ((label.contains("中标价") || label.contains("中标金额")) && item.getBidAmount() == null) {
            item.setBidAmount(value.replaceAll("[^\\d.]", ""));
        } else if (label.contains("中标日期") && item.getBidDate() == null) {
            item.setBidDate(normalizeDateTime(value));
        }
    }

    static String regexFirst(String text, String regex) {
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) {
                String val = m.group(1).trim();
                return val.isBlank() ? null : val;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    static String normalizeDateTime(String raw) {
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

    static String normalizePrice(String raw) {
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
