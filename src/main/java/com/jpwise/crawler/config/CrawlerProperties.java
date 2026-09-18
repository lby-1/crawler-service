package com.jpwise.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    private String authToken;
    private Schedule schedule = new Schedule();
    private ThreadPool threadPool = new ThreadPool();
    private int defaultPageLimit = 50;
    private int defaultReqInterval = 2;
    private Cleanup cleanup = new Cleanup();
    private Jianyu jianyu = new Jianyu();
    private ExternalBusiness externalBusiness = new ExternalBusiness();
    private Map<String, SiteConfig> sites;

    @Data
    public static class Schedule {
        private boolean enabled = true;
        private String cron = "0 0 */2 * * ?";
    }

    @Data
    public static class ThreadPool {
        private int coreSize = 3;
        private int maxSize = 5;
    }

    @Data
    public static class Cleanup {
        private int resultKeepDays = 7;
        private int logKeepDays = 30;
    }

    @Data
    public static class Jianyu {
        /** 是否启用剑鱼竞争对手爬取 */
        private boolean enabled = false;
        /** 剑鱼竞争对手招投标数据定时爬取配置 */
        private JianyuSchedule schedule = new JianyuSchedule();
        /** 剑鱼列表接口 */
        private String apiUrl = "https://api.jianyu360.com/data/biddata/list";
        /** 剑鱼详情接口 */
        private String infoUrl = "https://api.jianyu360.com/data/biddata/info";
        /** 剑鱼 appid */
        private String appid;
        /** 剑鱼密钥 */
        private String key;
        /** 只抓取竞争对手中标/招标情况 */
        private String subType = "中标,招标";
        /** 关键词范围，剑鱼列表接口必填 */
        private String keywordScope = "公告标题,公告正文,标的物,项目名称,采购单位,中标单位";
        /** 可选行业过滤 */
        private String industry = "";
        /** 默认回溯天数 */
        private int defaultDays = 1;
        /** 默认最多翻页次数，避免一次任务过大 */
        private int defaultMaxPages = 20;
        /** 剑鱼请求间隔，单位秒 */
        private int reqInterval = 1;
        /** 返回给调用方的错误明细上限 */
        private int maxErrorMessages = 20;
    }

    @Data
    public static class JianyuSchedule {
        /** 是否启用剑鱼竞争对手招投标数据定时爬取 */
        private boolean enabled = false;
        /** 每天凌晨 2:30 爬取一次已有竞争对手招投标数据 */
        private String cron = "0 30 2 * * ?";
    }

    @Data
    public static class ExternalBusiness {
        /** 是否启用外网平台业务库第二数据源 */
        private boolean enabled = false;
        /** 爬虫写入外网平台时使用的创建人ID */
        private String createdBy = "crawler-service";
        /** 爬虫写入外网平台时使用的创建人名称 */
        private String createdByName = "爬虫服务";
        /** 爬虫写入外网平台时使用的更新人ID */
        private String updatedBy = "crawler-service";
        /** 爬虫写入外网平台时使用的更新人名称 */
        private String updatedByName = "爬虫服务";
        /** 外网平台租户ID */
        private String tenantId = "crawler-service";
        /** 外网平台部门ID，可按实际环境配置 */
        private String deptId = "";
        /** 可选默认行业 */
        private String defaultIndustry = "";
        /** 可选默认业务类型 */
        private String defaultBusinessType = "";
    }

    @Data
    public static class SiteConfig {
        private String name;
        private String baseUrl;
        /** 所属省份（固定值，如"广东"、"广西"） */
        private String province = "";
        /** 所属城市（固定值，留空则从API数据中取） */
        private String city = "";
        /** 引擎类型：html / api / playwright */
        private String engineType = "html";
        /** 每次爬取最大页数（默认3） */
        private int maxPages = 3;
        /** HTML模式：列表页CSS选择器配置 */
        private ListSelectors listSelectors = new ListSelectors();
        /** HTML模式：详情页CSS选择器配置 */
        private DetailSelectors detailSelectors = new DetailSelectors();
        /** HTML模式：分页URL模式 */
        private String pagePattern = "index.jhtml";
        /** API模式：接口配置 */
        private ApiConfig apiConfig;
        /** Playwright模式：动态JS页面配置 */
        private PlaywrightConfig playwrightConfig;
        private List<CategoryConfig> categories;
    }

    @Data
    public static class CategoryConfig {
        private String name;
        private String url;
        private String code;
    }

    /**
     * 列表页选择器 — 每个站点可以不同
     */
    @Data
    public static class ListSelectors {
        /** 列表行选择器（每行一条数据） */
        private String row = "table.public-table tr";
        /** 行内标题链接选择器 */
        private String title = "td a.title";
        /** 标题文本取法：text / attr:title / attr:xxx */
        private String titleAttr = "attr:title";
        /** 行内链接选择器（如果和title相同则留空） */
        private String link = "";
        /** 链接属性名 */
        private String linkAttr = "href";
        /** 行内日期选择器 */
        private String date = "td span";
    }

    /**
     * API 型站点配置 — 通过 JSON API 获取数据（适用于 SPA 网站）
     */
    @Data
    public static class ApiConfig {
        /** POST 列表接口URL */
        private String apiUrl;
        /** 每页条数 */
        private int pageSize = 10;
        /** 请求体模板（JSON），支持 {pageNo}/{pageSize}/{categoryCode} 占位符 */
        private String requestBody;
        /** 响应数据列表的JSON路径，如 "data.pageData" */
        private String dataPath = "data.pageData";
        /** 响应总数的JSON路径 */
        private String totalPath = "data.total";
        /** 字段映射：CrawlItem字段 → JSON字段名 */
        private Map<String, String> fieldMapping = new HashMap<>();
    }

    /**
     * Playwright 型站点配置 — 通过 headless 浏览器渲染 JS 页面
     * 适用于有百度安全验证等反爬措施的 SPA 网站
     */
    @Data
    public static class PlaywrightConfig {
        /** 列表容器选择器（等待此元素出现后开始提取） */
        private String listContainer = ".ninfo_list";
        /** 列表项选择器 */
        private String itemSelector = ".ninfo_list li";
        /** 标题选择器（相对于列表项） */
        private String titleSelector = "a";
        /** 日期选择器（相对于列表项） */
        private String dateSelector = "span";
        /** "加载更多"��钮选择器 */
        private String loadMoreSelector = ".load-more";
        /** 页面加载等待时间（毫秒） */
        private int waitTimeout = 10000;
        /** 点击"加载更多"后等待时间（毫秒） */
        private int loadMoreWait = 3000;
        
        /** 代理服务器地址，如 http://tps123.kdlapi.com:15818 */
        private String proxyServer;
        /** 代理认证用户名 */
        private String proxyUsername;
        /** 代理认证密码 */
        private String proxyPassword;
    }

    /**
     * 详情页选择器 — 每个站点可以不同
     */
    @Data
    public static class DetailSelectors {
        /** 正文容器选择器 */
        private String content = "div.detail-content-main";
        /** 发布时间选择器 */
        private String publishTime = "meta[name=PubDate]";
        /** 发布时间取法：text / attr:content */
        private String publishTimeAttr = "attr:content";
        /** 附件列表选择器 */
        private String attachments = "div.attachments ul li a";
    }
}
