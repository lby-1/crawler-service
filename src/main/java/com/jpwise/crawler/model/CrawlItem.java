package com.jpwise.crawler.model;

import lombok.Data;

@Data
public class CrawlItem {
    private String title;
    private String sourceUrl;
    private String publishTime;
    private String summary;
    private String detail;
    private String category;
    private String categoryCode;
    private String siteName;
    private String province;
    private String city;
    private String urlHash;
    private String webUrl; // 真实的浏览器页面跳转地址（如应对 SPA 架构前后的连接分离）
    private String spiderStatus; // 详情获取状态：空/已获取

    // v4 结构化字段 — 从详情正文中正则提取
    private String projectName;     // 招标项目名称
    private String sectionName;     // 标段(包)名称
    private String maxPrice;        // 最高投标限价（万元）
    private String openTime;        // 开标时间
    private String openPlace;       // 开标地点
    private String tenderer;        // 招标人
    private String tenderContact;   // 招标人联系人
    private String tenderTel;       // 联系电话
    private String bidWinner;       // 中标单位
    private String bidAmount;       // 中标价（金额）
    private String bidDate;         // 中标日期
}
