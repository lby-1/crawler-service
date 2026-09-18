package com.jpwise.crawler.engine.handler.site.gxggzy;

import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广西公共资源交易中心 - 字段提取工具类
 * 广西详情页特点：
 *   1. 正文在 div.ewb-details-info 中
 *   2. 多数内容是 <p> 标签的纯文本，用中文编号分段（一、二、三...）
 *   3. 少数页面含有表格
 *   4. 招标人/联系人/电话等信息在正文末尾
 */
@Slf4j
public class GxggzyExtractUtils {

    /**
     * 从 HTML 表格中提取字段（部分页面有表格格式）
     */
    public static void extractFromTable(CrawlItem item, Element contentEl) {
        Elements tables = contentEl.select("table");
        if (tables.isEmpty()) return;

        for (Element table : tables) {
            for (Element row : table.select("tr")) {
                Elements cells = row.select("td, th");
                if (cells.size() >= 2) {
                    String label = cells.get(0).text().trim();
                    String value = cells.get(1).text().trim();
                    if (label.isBlank() || value.isBlank()) continue;

                    matchField(item, label, value);

                    // 4列格式
                    if (cells.size() >= 4) {
                        String label2 = cells.get(2).text().trim();
                        String value2 = cells.get(3).text().trim();
                        if (!label2.isBlank() && !value2.isBlank()) {
                            matchField(item, label2, value2);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从纯文本提取通用字段（适用于所有分类）
     */
    public static void extractFromPlainText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 项目名称
        if (item.getProjectName() == null) {
            String val = regexFirst(text, "项目名称[：:]+\\s*(.+?)(?=[\\s]*[二三四五六七八九十]、|$)");
            if (val != null && val.length() >= 4) item.setProjectName(val);
        }

        // 招标人
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "招标人[：:]+\\s*([^\\n，。,;；联系]{2,50}?)(?=[\\s联系，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "招标单位[：:]+\\s*([^\\n，。,;；]{2,50}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "建设单位[：:]+\\s*([^\\n，。,;；]{2,50}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "采购人[：:]+\\s*([^\\n，。,;；]{2,50}?)(?=[\\s，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }

        // 联系人（招标人的联系人，排除代理机构的）
        if (item.getTenderContact() == null) {
            // 优先匹配"招标人：XXX联系人：YYY"模式
            String val = regexFirst(text, "招标人[：:].*?联系人[：:]+\\s*([^\\n，。,;；电话传]{2,20}?)(?=[\\s，。,;；电话传\\n]|$)");
            if (val != null) {
                item.setTenderContact(val);
            } else {
                // 取第一个联系人
                val = regexFirst(text, "联系人[：:]+\\s*([^\\n，。,;；电话传]{2,20}?)(?=[\\s，。,;；电话传\\n]|$)");
                if (val != null) item.setTenderContact(val);
            }
        }

        // 联系电话（招标人的电话）
        if (item.getTenderTel() == null) {
            // 优先匹配"招标人：XXX...电话：YYY"模式
            String val = regexFirst(text, "招标人[：:].*?电话[：:]+\\s*([\\d\\-()（）]{7,20})");
            if (val != null) {
                item.setTenderTel(val);
            } else {
                val = regexFirst(text, "(?:联系)?电话[：:]+\\s*([\\d\\-()（）]{7,20})");
                if (val != null) item.setTenderTel(val);
            }
        }

        // 开标时间
        if (item.getOpenTime() == null) {
            String val = regexFirst(text, "开标时间[：:]+\\s*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日\\s]*(?:\\d{1,2}[时:：]\\d{1,2}(?:[分:：]\\d{1,2}秒?)?)?)");
            if (val != null) item.setOpenTime(normalizeDateTime(val));
        }

        // 开标地点（也匹配"工程建设地点"、"项目地点"）
        if (item.getOpenPlace() == null) {
            String val = regexFirst(text, "开标地点[：:]+\\s*(.+?)(?=[。，,\\n]|[一二三四五六七八九十]、|$)");
            if (val == null || val.length() < 4) {
                val = regexFirst(text, "(?:工程建设地点|项目地点|建设地点)[：:]+\\s*(.+?)(?=[。，,\\n]|\\d\\.|[一二三四五六七八九十]、|$)");
            }
            if (val != null && val.length() >= 2) item.setOpenPlace(val);
        }

        // 城市提取（多策略）
        if (item.getCity() == null || item.getCity().isBlank()) {
            String city = null;
            // 策略1：从项目地点提取
            if (item.getOpenPlace() != null) {
                city = regexFirst(item.getOpenPlace(), "([\\u4e00-\\u9fa5]{2,6}(?:市|州|地区|盟))");
                if (city != null) log.debug("从项目地点提取城市: {}", city);
            }
            // 策略2：从招标人名称提取（如"河池市龙江河谷灌区开发建设有限公司" → "河池市"）
            if (city == null && item.getTenderer() != null) {
                city = regexFirst(item.getTenderer(), "^([\\u4e00-\\u9fa5]{2,6}(?:市|州|地区|盟))");
                if (city != null) log.debug("从招标人名称提取城市: {}", city);
            }
            // 策略3：从正文中直接找"XX市"
            if (city == null) {
                city = regexFirst(text, "(?:项目所在地|工程所在地|建设地点|项目地点)[：:]+\\s*.*?([\\u4e00-\\u9fa5]{2,6}(?:市|州|地区|盟))");
                if (city != null) log.debug("从正文标签提取城市: {}", city);
            }
            if (city != null) item.setCity(city);
        }

        // 标段名称
        if (item.getSectionName() == null) {
            String val = regexFirst(text, "标段[（(包)）]*名称[：:]+\\s*(.+?)(?=[。，,\\n]|[一二三四五六七八九十]、|$)");
            if (val != null && val.length() >= 2) item.setSectionName(val);
        }

        // 最高投标限价（也匹配"概算总投资"、"项目概算总投资"，统一转为万元）
        if (item.getMaxPrice() == null) {
            String val = regexFirst(text, "(?:最高投标限价|最高限价|控制价)[：:]+\\s*([\\d,\\.]+\\s*(?:万元|亿元|元)?)");
            if (val == null) {
                val = regexFirst(text, "(?:项目概算总投资|概算总投资|总投资|合同估算价)[：:约为]*\\s*([\\d,\\.]+\\s*(?:万元|亿元|元))");
            }
            if (val != null) {
                item.setMaxPrice(normalizePrice(val));
            }
        }
    }

    /**
     * 成交公示特有：提取第一中标候选人信息
     * 格式：第一中标候选人：XXX公司 投标总报价：XXX元
     */
    public static void extractBidCandidateFromText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 第一中标候选人
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "第一中标候选人[：:]+\\s*([^\\n投标资质]{2,80}?)(?=[\\s投标资质]|$)");
            if (val != null) {
                item.setBidWinner(val.trim());
                log.debug("提取第一中标候选人: {}", val);
            }
        }

        // 投标总报价（第一候选人的）
        if (item.getBidAmount() == null) {
            // 先找"小写：¥6,385,000.00"格式
            String val = regexFirst(text, "第一中标候选人.*?小写[：:]*[¥￥]?([\\d,]+\\.?\\d*)");
            if (val != null) {
                item.setBidAmount(val.replace(",", ""));
                log.debug("提取中标价(小写): {}", val);
            } else {
                // 再找"投标报价：XXX元"
                val = regexFirst(text, "第一中标候选人.*?投标(?:总)?报价[：:].*?([\\d,]+\\.?\\d*)\\s*元");
                if (val != null) {
                    item.setBidAmount(val.replace(",", ""));
                    log.debug("提取中标价(投标报价): {}", val);
                }
            }
        }

        // 评标日期 → bidDate
        if (item.getBidDate() == null) {
            String val = regexFirst(text, "评标日期[：:]+\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日)");
            if (val != null) item.setBidDate(normalizeDateTime(val));
        }
    }

    /**
     * 中标结果公示特有：提取中标人、中标价
     * 格式：中标人：XXX公司 中标价：XXX元
     */
    public static void extractBidResultFromText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        // 中标人
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "中标人[：:]+\\s*([^\\n，。,;；中标价]{2,80}?)(?=[\\s，。,;；中标价\\n]|$)");
            if (val != null) {
                item.setBidWinner(val.trim());
                log.debug("提取中标人: {}", val);
            }
        }
        // fallback: 第一中标候选人
        if (item.getBidWinner() == null) {
            extractBidCandidateFromText(item, text);
        }

        // 中标价
        if (item.getBidAmount() == null) {
            // 先找"中标价：XXX元"
            String val = regexFirst(text, "中标(?:价格?|金额)[：:]+\\s*([\\d,\\.]+)\\s*(?:万元|亿元|元)?");
            if (val != null) {
                item.setBidAmount(val.replace(",", ""));
                log.debug("提取中标价: {}", val);
            } else {
                // 再找"小写：¥XXX"
                val = regexFirst(text, "小写[：:]*[¥￥]?([\\d,]+\\.?\\d*)");
                if (val != null) {
                    item.setBidAmount(val.replace(",", ""));
                    log.debug("提取中标价(小写): {}", val);
                }
            }
        }

        // 中标日期
        if (item.getBidDate() == null) {
            String val = regexFirst(text, "(?:中标日期|定标日期|定标时间)[：:]+\\s*(\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}日?)");
            if (val != null) item.setBidDate(normalizeDateTime(val));
        }
    }

    // ---- private helpers ----

    private static void matchField(CrawlItem item, String label, String value) {
        if (label.contains("项目名称") && item.getProjectName() == null) {
            item.setProjectName(value);
        } else if ((label.contains("标段") || label.contains("包")) && label.contains("名称") && item.getSectionName() == null) {
            item.setSectionName(value);
        } else if ((label.contains("最高") && (label.contains("限价") || label.contains("控制价")))
                || label.contains("概算总投资") || label.contains("合同估算价")) {
            if (item.getMaxPrice() == null) item.setMaxPrice(normalizePrice(value));
        } else if ((label.contains("项目地点") || label.contains("工程建设地点") || label.contains("建设地点"))
                && item.getOpenPlace() == null) {
            item.setOpenPlace(value);
            // 同时提取城市
            if (item.getCity() == null || item.getCity().isBlank()) {
                String city = regexFirst(value, "([\\u4e00-\\u9fa5]{2,6}(?:市|州|地区|盟))");
                if (city != null) item.setCity(city);
            }
        } else if (label.contains("开标时间") && item.getOpenTime() == null) {
            item.setOpenTime(normalizeDateTime(value));
        } else if (label.contains("开标地点") && item.getOpenPlace() == null) {
            item.setOpenPlace(value);
        } else if (label.contains("招标人") && !label.contains("代理") && item.getTenderer() == null) {
            item.setTenderer(value);
        } else if (label.contains("联系人") && item.getTenderContact() == null) {
            item.setTenderContact(value);
        } else if ((label.contains("联系电话") || label.contains("电话")) && item.getTenderTel() == null) {
            item.setTenderTel(value);
        } else if ((label.contains("中标人") || label.contains("中标单位")) && item.getBidWinner() == null) {
            item.setBidWinner(value);
        } else if ((label.contains("中标价") || label.contains("中标金额") || label.contains("成交价")) && item.getBidAmount() == null) {
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

    /**
     * 金额统一为万元
     * 输入："6385000元" → "638.50"（万元）
     *       "638.5万元" → "638.5"（已是万元）
     *       "6.385亿元" → "6385"（万元）
     *       "638.5" → "638.5"（无单位，原样返回）
     */
    static String normalizePrice(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String s = raw.trim().replace(",", "");

        Matcher m = Pattern.compile("([\\d.]+)\\s*(万元|亿元|元)?").matcher(s);
        if (!m.find()) return s;

        String numStr = m.group(1);
        String unit = m.group(2);

        try {
            double num = Double.parseDouble(numStr);
            if ("元".equals(unit)) {
                // 元 → 万元
                num = num / 10000.0;
                return String.format("%.2f", num);
            } else if ("亿元".equals(unit)) {
                // 亿元 → 万元
                num = num * 10000.0;
                return String.format("%.2f", num);
            }
            // 万元或无单位，原样返回
            return numStr;
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
