package com.jpwise.crawler.engine.handler.site.ynggzy;

import com.alibaba.fastjson2.JSONObject;
import com.jpwise.crawler.model.CrawlItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云南省公共资源交易中心 - 字段提取工具类
 * 云南详情API特点：
 *   1. 详情API返回JSON，包含结构化字段 + bulletincontent（HTML正文）
 *   2. bulletincontent中有标准表格（label:value格式）
 *   3. JSON顶层有 bidopentime/jyptid/tenderprojectname 等直接字段
 *   4. bidopentime格式为 "20260414090000"（yyyyMMddHHmmss）
 */
@Slf4j
public class YnggzyExtractUtils {

    /**
     * 从JSON顶层字段提取（所有分类通用）
     */
    /**
     * 从JSON中提取HTML正文（兼容 bulletincontent 和 content 两种字段名）
     */
    public static String getHtmlContent(JSONObject json) {
        if (json == null) return null;
        String html = json.getString("bulletincontent");
        if (html == null || html.isBlank()) html = json.getString("content");
        return html;
    }

    public static void extractFromJson(CrawlItem item, JSONObject json) {
        if (json == null) return;

        // 城市
        String jyptid = json.getString("jyptid");
        if (jyptid != null && !jyptid.isBlank() && (item.getCity() == null || item.getCity().isBlank())) {
            item.setCity(jyptid);
        }

        // 项目名称（中标结果有 tenderprojectname，招标计划有 tenderProjectName）
        for (String key : List.of("tenderprojectname", "tenderProjectName", "projectName")) {
            String val = json.getString(key);
            if (val != null && !val.isBlank() && item.getProjectName() == null) {
                item.setProjectName(val);
                break;
            }
        }

        // 发布时间（紧凑格式 20260324152045 或标准格式 2026-03-24）
        if (item.getPublishTime() == null || item.getPublishTime().isBlank()) {
            for (String key : List.of("bulletinissuetime", "publishTime")) {
                String val = json.getString(key);
                if (val != null && !val.isBlank()) {
                    // 如果是紧凑格式（纯数字且长度>=8）
                    if (val.matches("\\d{8,14}")) {
                        item.setPublishTime(formatCompactDateTime(val));
                    } else {
                        item.setPublishTime(val);
                    }
                    break;
                }
            }
        }

        // 开标时间（格式 20260414090000）
        String bidopentime = json.getString("bidopentime");
        if (bidopentime != null && bidopentime.length() >= 8 && item.getOpenTime() == null) {
            item.setOpenTime(formatCompactDateTime(bidopentime));
        }
        // 递交截止时间作为开标时间 fallback
        if (item.getOpenTime() == null) {
            String deadline = json.getString("biddocreferendtime");
            if (deadline != null && !deadline.isBlank()) {
                if (deadline.matches("\\d{8,14}")) {
                    item.setOpenTime(formatCompactDateTime(deadline));
                } else {
                    item.setOpenTime(deadline);
                }
            }
        }

        // 中标人（中标结果公告）
        String winner = json.getString("winbiddername");
        if (winner != null && !winner.isBlank() && item.getBidWinner() == null) {
            item.setBidWinner(winner);
        }

        // 中标金额（单位：元，转为字符串）
        Double bidamount = json.getDouble("bidamount");
        if (bidamount != null && bidamount > 0 && item.getBidAmount() == null) {
            item.setBidAmount(String.valueOf(bidamount));
        }

        // 中标日期：用 bulletinissuetime 作为 bidDate（中标结果公告场景）
        String bulletinType = json.getString("bulletintype");
        if ("3".equals(bulletinType) && item.getBidDate() == null) {
            String issueTime = json.getString("bulletinissuetime");
            if (issueTime != null && !issueTime.isBlank()) {
                item.setBidDate(issueTime.length() > 10 ? issueTime.substring(0, 10) : issueTime);
            }
        }

        // 注意：不使用API返回的url字段（带/#/会导致跳转首页），HREF由配置模板生成
    }

    /**
     * 从 bulletincontent（HTML）中提取结构化字段
     */
    public static void extractFromHtmlContent(CrawlItem item, String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) return;

        Document doc = Jsoup.parse(htmlContent);
        String textContent = doc.text();
        item.setDetail(textContent);
        item.setSummary(textContent.length() > 500 ? textContent.substring(0, 500) + "..." : textContent);

        // 从表格提取
        extractFromTable(item, doc.body());
        // 从纯文本 fallback
        extractFromPlainText(item, textContent);
    }

    /**
     * 从HTML表格提取字段
     */
    public static void extractFromTable(CrawlItem item, Element container) {
        Elements tables = container.select("table");
        log.debug("云南详情：找到 {} 个表格", tables.size());
        for (Element table : tables) {
            Elements rows = table.select("tr");
            // 检测标段信息子表（序号/标段名称/... 格式）
            if (rows.size() >= 2) {
                Element headerRow = rows.get(0);
                String headerText = headerRow.text();
                if (headerText.contains("序号") && headerText.contains("标段名称")) {
                    // 找到"标段名称"在表头的列索引
                    Elements headerCells = headerRow.select("td, th");
                    int nameIdx = -1, priceIdx = -1;
                    for (int c = 0; c < headerCells.size(); c++) {
                        String h = headerCells.get(c).text().trim();
                        if (h.contains("标段名称") && nameIdx < 0) nameIdx = c;
                        if (h.contains("估算价") && priceIdx < 0) priceIdx = c;
                    }
                    if (nameIdx >= 0) {
                        for (int i = 1; i < rows.size(); i++) {
                            Elements cells = rows.get(i).select("td, th");
                            if (cells.size() > nameIdx) {
                                String sectionName = cells.get(nameIdx).text().trim();
                                if (!sectionName.isBlank() && item.getSectionName() == null) {
                                    item.setSectionName(sectionName);
                                    log.debug("从标段子表提取标段名称: {}", sectionName);
                                }
                            }
                            if (priceIdx >= 0 && cells.size() > priceIdx) {
                                String price = cells.get(priceIdx).text().trim();
                                if (!price.isBlank() && item.getMaxPrice() == null) {
                                    item.setMaxPrice(price);
                                    log.debug("从标段子表提取合同估算价: {}", price);
                                }
                            }
                        }
                    }
                    continue;
                }
            }

            for (Element row : rows) {
                Elements cells = row.select("td, th");
                if (cells.size() >= 2) {
                    String label = cells.get(0).text().trim();
                    // 去掉label末尾的冒号（兼容中英文冒号）
                    String cleanLabel = label.replaceAll("[：:]+$", "").trim();
                    String value = cells.get(1).text().trim();
                    if (cleanLabel.isBlank() || value.isBlank()) continue;

                    log.debug("云南表格字段: [{}] = [{}]", cleanLabel,
                            value.length() > 50 ? value.substring(0, 50) + "..." : value);
                    matchField(item, cleanLabel, value);

                    // 4列格式
                    if (cells.size() >= 4) {
                        String label2 = cells.get(2).text().trim().replaceAll("[：:]+$", "").trim();
                        String value2 = cells.get(3).text().trim();
                        if (!label2.isBlank() && !value2.isBlank()) {
                            log.debug("云南表格字段(4列): [{}] = [{}]", label2,
                                    value2.length() > 50 ? value2.substring(0, 50) + "..." : value2);
                            matchField(item, label2, value2);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从纯文本提取（fallback）
     */
    public static void extractFromPlainText(CrawlItem item, String text) {
        if (text == null || text.isBlank()) return;

        if (item.getProjectName() == null) {
            String val = regexFirst(text, "(?:招标项目名称|项目名称)[：:]+\\s*(.+?)(?=[\\s]*(?:资金来源|建设规模|标段|招标方式)|$)");
            if (val != null && val.length() >= 4) item.setProjectName(val);
        }
        if (item.getTenderer() == null) {
            String val = regexFirst(text, "(?:建设单位|招标人)[：:]+\\s*([^\\n，。,;；经办]{2,50}?)(?=[\\s经办，。,;；\\n]|$)");
            if (val != null) item.setTenderer(val);
        }
        if (item.getTenderContact() == null) {
            String val = regexFirst(text, "经办人[：:]+\\s*([^\\n，。,;；办公]{2,20}?)(?=[\\s办公，。,;；\\n]|$)");
            if (val != null) item.setTenderContact(val);
        }
        if (item.getTenderTel() == null) {
            String val = regexFirst(text, "(?:办公电话|移动电话)[：:]+\\s*([\\d\\-()（）]{7,20})");
            if (val != null) item.setTenderTel(val);
        }
        if (item.getBidWinner() == null) {
            String val = regexFirst(text, "中标人[：:]+\\s*([^\\n，。,;；中标]{2,80}?)(?=[\\s中标，。,;；\\n]|$)");
            if (val != null) item.setBidWinner(val);
        }
        if (item.getBidAmount() == null) {
            // 先找 "中标价：800.983557万元"
            String val = regexFirst(text, "中标价[（(费率或单价等)）]*[：:]+\\s*([\\d,\\.]+)\\s*(?:万元|亿元|元)");
            if (val != null) {
                item.setBidAmount(val.replace(",", ""));
            } else {
                // 再找备注中的 "中标价：800.983557万元"
                val = regexFirst(text, "中标价[：:]+\\s*([\\d,\\.]+)\\s*(?:万元|亿元|元)");
                if (val != null) item.setBidAmount(val.replace(",", ""));
            }
        }

        // 从备注文本中提取招标人（中标结果公告场景：备注中有"XXX局研究决定"）
        if (item.getTenderer() == null) {
            // 匹配 "XX局/XX站/XX中心 研究决定"
            String val = regexFirst(text, "([\\u4e00-\\u9fa5]{4,30}(?:局|站|中心|处|院|办|厅|委|部|公司))\\s*研究决定");
            if (val != null) {
                item.setTenderer(val);
                log.debug("从备注提取招标人: {}", val);
            }
        }

        // 从备注文本中提取中标日期（"于2026年3月20日开评标"）
        if (item.getBidDate() == null) {
            String val = regexFirst(text, "于(\\d{4}年\\d{1,2}月\\d{1,2}日)开评标");
            if (val != null) item.setBidDate(normalizeDateTime(val));
        }
    }

    // ---- private helpers ----

    private static void matchField(CrawlItem item, String label, String value) {
        if (label.contains("招标项目名称") || label.equals("项目名称")) {
            if (item.getProjectName() == null) item.setProjectName(value);
        } else if (label.contains("标段名称") || label.contains("标段编号")) {
            // 标段名称优先，标段编号仅在无名称时使用
            if (label.contains("名称") && item.getSectionName() == null) item.setSectionName(value);
        } else if (label.contains("合同估算价") || label.contains("最高限价") || label.contains("最高投标限价") || label.contains("控制价")) {
            if (item.getMaxPrice() == null) item.setMaxPrice(normalizePrice(value));
        } else if (label.contains("开标地点") || label.contains("交易地点") || label.contains("项目行政主管地区")) {
            if (item.getOpenPlace() == null) item.setOpenPlace(value);
        } else if (label.contains("递交投标文件截止时间") || label.equals("开标时间")
                || label.contains("预计招标公告发布时间")) {
            if (item.getOpenTime() == null) item.setOpenTime(normalizeDateTime(value));
        } else if (label.contains("招标人联系人")) {
            // 必须在"招标人"判断之前！
            if (item.getTenderContact() == null) item.setTenderContact(value);
        } else if (label.contains("招标人联系电话")) {
            if (item.getTenderTel() == null) item.setTenderTel(value);
        } else if ((label.equals("建设单位") || label.equals("招标人")) && !label.contains("代理")) {
            if (item.getTenderer() == null) item.setTenderer(value);
        } else if (label.contains("经办人") && item.getTenderContact() == null) {
            item.setTenderContact(value);
        } else if ((label.contains("办公电话") || label.contains("移动电话")) && item.getTenderTel() == null) {
            item.setTenderTel(value);
        } else if (label.contains("监督部门")) {
            // "监督部门及联系方式：XX局(电话)" → 招标人 fallback + 电话
            if (item.getTenderer() == null) {
                // 去掉括号中的电话部分
                String org = value.replaceAll("[（(][\\d\\-/]+[)）]", "").trim();
                if (!org.isBlank() && org.length() >= 4) {
                    item.setTenderer(org);
                    log.debug("从监督部门提取招标人: {}", org);
                }
            }
            String tel = regexFirst(value, "([\\d\\-]{7,20})");
            if (tel != null && item.getTenderTel() == null) item.setTenderTel(tel);
        } else if (label.contains("中标人") && !label.contains("代码") && item.getBidWinner() == null) {
            item.setBidWinner(value);
        } else if (label.contains("中标价") && item.getBidAmount() == null) {
            // 云南中标价可能是费率格式，只取数字
            String amount = regexFirst(value, "([\\d,\\.]+)");
            if (amount != null) item.setBidAmount(amount.replace(",", ""));
        } else if (label.contains("中标日期") && item.getBidDate() == null) {
            item.setBidDate(normalizeDateTime(value));
        } else if (label.contains("监督部门") && item.getTenderTel() == null) {
            // 监督部门联系方式格式："XX局（0888-5521936）"
            String tel = regexFirst(value, "([\\d\\-]{7,20})");
            // 不覆盖已有电话，仅作为备选
        }
    }

    /**
     * 格式化紧凑日期时间：20260414090000 → 2026-04-14 09:00:00
     */
    static String formatCompactDateTime(String compact) {
        if (compact == null) return null;
        compact = compact.trim();
        if (compact.length() >= 14) {
            return compact.substring(0, 4) + "-" + compact.substring(4, 6) + "-" + compact.substring(6, 8)
                    + " " + compact.substring(8, 10) + ":" + compact.substring(10, 12) + ":" + compact.substring(12, 14);
        } else if (compact.length() >= 8) {
            return compact.substring(0, 4) + "-" + compact.substring(4, 6) + "-" + compact.substring(6, 8);
        }
        return compact;
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
            return numStr; // 万元或无单位
        } catch (NumberFormatException e) { return s; }
    }
}
