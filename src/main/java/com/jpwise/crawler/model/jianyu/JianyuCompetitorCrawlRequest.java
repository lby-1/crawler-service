package com.jpwise.crawler.model.jianyu;

import lombok.Data;

@Data
public class JianyuCompetitorCrawlRequest {

    /** 指定竞争对手名称；为空时读取外网平台竞争对手企业表 */
    private String competitorName;
    /** 发布开始时间，Unix 秒 */
    private Long publishtimeStart;
    /** 发布结束时间，Unix 秒 */
    private Long publishtimeEnd;
    /** 覆盖默认回溯天数 */
    private Integer days;
    /** 覆盖默认最大翻页次数 */
    private Integer maxPages;
    /** 覆盖默认请求间隔，单位秒 */
    private Integer reqInterval;
}
