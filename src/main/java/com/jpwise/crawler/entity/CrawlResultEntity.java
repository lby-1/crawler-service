package com.jpwise.crawler.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.Date;

@Data
@TableName("t_collect_result")
public class CrawlResultEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("TASKID")
    private String taskId;

    @TableField("SITEID")
    private String siteId;

    @TableField("SITENAME")
    private String siteName;

    @TableField("TITLE")
    private String title;

    @TableField("SUMMARY")
    private String summary;

    @TableField("SOURCEURL")
    private String sourceUrl;

    @TableField("PUBLISHTIME")
    private Date publishTime;

    @TableField("COLLECTTIME")
    private Date collectTime;

    @TableField("HITKEYWORDS")
    private String hitKeywords;

    @TableField("READSTATUS")
    private Integer readStatus;

    @TableField("URLHASH")
    private String urlHash;

    @TableField("CATEGORY")
    private String category;

    @TableField("CATEGORYCODE")
    private String categoryCode;

    @TableField("DETAIL")
    private String detail;

    @TableField("HREF")
    private String href;

    @TableField("PROVINCE")
    private String province;

    @TableField("CITY")
    private String city;

    @TableField("SPIDERSTATUS")
    private String spiderStatus;

    @TableField("SYNCED")
    private Integer synced;

    @TableField("BIDAMOUNT")
    private String bidAmount;

    @TableField("BIDWINNER")
    private String bidWinner;

    @TableField("BIDDATE")
    private String bidDate;

    @TableField("PROJECTNAME")
    private String projectName;

    @TableField("SECTIONNAME")
    private String sectionName;

    @TableField("MAXPRICE")
    private String maxPrice;

    @TableField("OPENTIME")
    private String openTime;

    @TableField("OPENPLACE")
    private String openPlace;

    @TableField("TENDERER")
    private String tenderer;

    @TableField("TENDERCONTACT")
    private String tenderContact;

    @TableField("TENDERTEL")
    private String tenderTel;

    @TableField("CREATORTIME")
    private Date creatorTime;
}
