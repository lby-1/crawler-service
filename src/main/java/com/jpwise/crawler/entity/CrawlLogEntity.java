package com.jpwise.crawler.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.Date;

@Data
@TableName("t_collect_log")
public class CrawlLogEntity {

    @TableId(value = "ID", type = IdType.INPUT)
    private String id;

    @TableField("TASKID")
    private String taskId;

    @TableField("SITEID")
    private String siteId;

    @TableField("SITENAME")
    private String siteName;

    @TableField("TRIGGERTYPE")
    private Integer triggerType;

    @TableField("EXECSTATUS")
    private Integer execStatus;

    @TableField("STARTTIME")
    private Date startTime;

    @TableField("ENDTIME")
    private Date endTime;

    @TableField("TOTALCOUNT")
    private Integer totalCount;

    @TableField("HITCOUNT")
    private Integer hitCount;

    @TableField("ERRORMSG")
    private String errorMsg;

    @TableField("SYNCED")
    private Integer synced;

    @TableField("CREATORTIME")
    private Date creatorTime;

    @TableField("CATEGORY")
    private String category;
}
