package com.jpwise.crawler.engine;

import com.jpwise.crawler.config.CrawlerProperties;
import com.jpwise.crawler.model.CrawlItem;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 爬取引擎 — 适用于有反爬保护的 JS 动态渲染页面
 * 典型站点：中国采购与招标网 (chinabidding.cn)，有百度安全验证 SDK
 *
 * 原理：启动 headless Chromium 渲染页面，等待 JS 执行完毕后从 DOM 提取数据
 * 支持两种翻页模式：URL翻页（pagePattern含{page}）和"加载更多"按钮
 */
@Slf4j
@Component
public class PlaywrightCrawlEngine {

    private Playwright playwright;
    private Browser browser;
    private String currentProxy;

    /**
     * 获取或创建 Browser 实例

    /**
     * 获取或创建 Browser 实例
     * 
     * 关键策略：
     * - 使用代理时：每次强制重建browser，让快代理隧道分配新IP
     * - 直连时：复用browser，减少启动开销
     */
    private synchronized Browser getBrowser(CrawlerProperties.PlaywrightConfig config) {
        String neededProxy = config != null ? config.getProxyServer() : null;
        if (neededProxy != null && !neededProxy.isBlank()) {
            if (!neededProxy.startsWith("http://") && !neededProxy.startsWith("socks")) {
                neededProxy = "http://" + neededProxy;
            }
        }

        boolean useProxy = neededProxy != null && !neededProxy.isBlank();
        
        // 使用代理时：强制重建browser，获取新IP（快代理隧道特性）
        // 直连时：复用browser，减少启动开销
        boolean needRebuild = false;
        if (useProxy) {
            // 代理模式：每次都重建，强制换IP
            needRebuild = true;
            if (browser != null) {
                log.info("代理模式：关闭旧browser，准备获取新IP...");
            }
        } else {
            // 直连模式：仅在browser不可用时重建
            needRebuild = (browser == null || !browser.isConnected() || currentProxy != null);
        }

        if (needRebuild) {
            if (browser != null) {
                try { browser.close(); } catch (Exception e) { /* ignore */ }
                browser = null;
            }
            if (playwright == null) {
                log.info("初始化 Playwright...");
                System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                playwright = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            }
            
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-dev-shm-usage",
                            "--disable-blink-features=AutomationControlled"
                    ));
            
            if (useProxy) {
                com.microsoft.playwright.options.Proxy pxy = new com.microsoft.playwright.options.Proxy(neededProxy);
                if (config.getProxyUsername() != null && !config.getProxyUsername().isEmpty()) {
                    pxy.setUsername(config.getProxyUsername());
                    pxy.setPassword(config.getProxyPassword() != null ? config.getProxyPassword() : "");
                }
                launchOptions.setProxy(pxy);
                log.info("启动浏览器（代理模式，新IP）: {}", neededProxy);
            } else {
                log.info("启动浏览器（直连模式）");
            }

            browser = playwright.chromium().launch(launchOptions);
            currentProxy = neededProxy;
        }
        return browser;
    }

    @PreDestroy
    public void destroy() {
        if (browser != null) {
            try { browser.close(); } catch (Exception e) { /* ignore */ }
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception e) { /* ignore */ }
        }
        log.info("Playwright 资源已释放");
    }

    /**
     * 爬取列表数据
     */
    public List<CrawlItem> crawlList(CrawlerProperties.SiteConfig site,
                                     CrawlerProperties.CategoryConfig category,
                                     int maxPages, int reqInterval) {
        CrawlerProperties.PlaywrightConfig config = site.getPlaywrightConfig();
        if (config == null) {
            config = new CrawlerProperties.PlaywrightConfig();
        }

        return doCrawlList(site, category, maxPages, reqInterval, config);
    }


    /**
     * 实际执行爬取（内部方法）
     */
    private List<CrawlItem> doCrawlList(CrawlerProperties.SiteConfig site,
                                        CrawlerProperties.CategoryConfig category,
                                        int maxPages, int reqInterval,
                                        CrawlerProperties.PlaywrightConfig config) {
        List<CrawlItem> allItems = new ArrayList<>();

        BrowserContext context = null;
        Page page = null;
        try {
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN");

            context = getBrowser(config).newContext(contextOptions);
            page = context.newPage();

            boolean useUrlPaging = site.getPagePattern() != null
                    && !site.getPagePattern().isBlank()
                    && site.getPagePattern().contains("{page}");

            for (int pageNum = 1; pageNum <= Math.max(1, maxPages); pageNum++) {
                String baseUrl = category.getUrl().trim();
                String url = pageNum == 1
                        ? baseUrl
                        : baseUrl + site.getPagePattern().replace("{page}", String.valueOf(pageNum));

                log.info("Playwright 访问第{}页: {}", pageNum, url);

                try {
                    // waitTimeout 配置单位是秒，转换为毫秒（Playwright API 需要毫秒）
                    long timeoutMs = config.getWaitTimeout() * 1000L;
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(timeoutMs));

                    // 等待页面渲染：首页等久一点（安全验证），后续页等短一点
                    page.waitForTimeout((pageNum == 1) ? 15000 : 5000);

                    List<CrawlItem> pageItems = extractItems(page, config, site, category);
                    log.info("第{}页提取到 {} 条数据", pageNum, pageItems.size());

                    if (pageItems.isEmpty()) break;
                    allItems.addAll(pageItems);
                } catch (Exception e) {
                    log.error("第{}页处理失败: {} - 错误: {}", pageNum, url, e.getMessage());
                    // 如果这是第一页且没有数据，尝试继续（可能是临时错误）
                    if (pageNum == 1 && allItems.isEmpty()) {
                        log.warn("首页处理失败，跳过此分类");
                        break;
                    }
                    // 如果是后续页，继续尝试下一页
                    continue;
                }

                // 加载更多模式
                if (!useUrlPaging) {
                    for (int i = 1; i < maxPages; i++) {
                        try {
                            String lms = config.getLoadMoreSelector();
                            if (lms == null || lms.isBlank()) break;
                            Locator loadMore = page.locator(lms);
                            if (loadMore.count() == 0 || !loadMore.first().isVisible()) break;
                            loadMore.first().click();
                            log.info("点击加载更多 (第{}次)", i);
                            page.waitForTimeout(config.getLoadMoreWait());
                        } catch (Exception e) {
                            break;
                        }
                    }
                    allItems = extractItems(page, config, site, category);
                    break;
                }

                if (reqInterval > 0) {
                    page.waitForTimeout(reqInterval * 1000);
                }
            }

            log.info("Playwright 共提取 {} 条数据: {} - {}", allItems.size(), site.getName(), category.getName());

        } catch (Exception e) {
            log.error("Playwright 爬取异常: {} - {} : {}", site.getName(), category.getName(), e.getMessage(), e);
        } finally {
            if (page != null) try { page.close(); } catch (Exception e) { /* ignore */ }
            if (context != null) try { context.close(); } catch (Exception e) { /* ignore */ }
        }

        return allItems;
    }

    /**
     * 从页面 DOM 中提取列表数据（使用 page.evaluate 在浏览器内执行 JS）
     */
    @SuppressWarnings("unchecked")
    private List<CrawlItem> extractItems(Page page,
                                         CrawlerProperties.PlaywrightConfig config,
                                         CrawlerProperties.SiteConfig site,
                                         CrawlerProperties.CategoryConfig category) {
        List<CrawlItem> items = new ArrayList<>();

        // 如果没有配置 Playwright 选择器，回退使用 list-selectors
        String itemSelector = config.getItemSelector();
        String titleSelector = config.getTitleSelector();
        String dateSelector = config.getDateSelector();
        
        if (itemSelector == null || itemSelector.isBlank()) {
            CrawlerProperties.ListSelectors listSel = site.getListSelectors();
            if (listSel != null) {
                itemSelector = listSel.getRow();
                titleSelector = listSel.getTitle();
                dateSelector = listSel.getDate();
                log.debug("使用 list-selectors 配置: item={}, title={}, date={}", itemSelector, titleSelector, dateSelector);
            }
        }
        
        // 确保选择器有默认值
        if (itemSelector == null || itemSelector.isBlank()) {
            itemSelector = "table.public-table tr";
            log.warn("未配置列表项选择器，使用默认值: {}", itemSelector);
        }
        if (titleSelector == null || titleSelector.isBlank()) {
            titleSelector = "td a.title";
            log.warn("未配置标题选择器，使用默认值: {}", titleSelector);
        }
        
        // 转义单引号防止 JS 注入
        String safeItemSelector = itemSelector.replace("'", "\\'");
        String safeTitleSelector = titleSelector.replace("'", "\\'");
        String safeDateSelector = dateSelector != null ? dateSelector.replace("'", "\\'") : "";

        Object result = page.evaluate("() => {\n" +
                "  const rows = document.querySelectorAll('" + safeItemSelector + "');\n" +
                "  const data = [];\n" +
                "  rows.forEach(row => {\n" +
                "    const link = row.querySelector('" + safeTitleSelector + "');\n" +
                "    if (!link) return;\n" +
                "    const title = link.getAttribute('title') || link.textContent.trim();\n" +
                "    if (!title) return;\n" +
                "    const href = link.getAttribute('href');\n" +
                "    // 增强日期提取：优先找指定的日期选择器，没配置则找最后一个td，最后尝试寻找父级或同级的span\n" +
                "    let date = '';\n" +
                "    const dateSelector = '" + safeDateSelector + "';\n" +
                "    if (dateSelector) {\n" +
                "      const dateEl = row.querySelector(dateSelector);\n" +
                "      if (dateEl) date = dateEl.textContent.trim();\n" +
                "    }\n" +
                "    if (!date) {\n" +
                "      const tds = row.querySelectorAll('td');\n" +
                "      if (tds.length > 0) date = tds[tds.length - 1].textContent.trim();\n" +
                "    }\n" +
                "    // 尝试提取省份（主要针对特定站点布局）\n" +
                "    const tds = row.querySelectorAll('td');\n" +
                "    const province = tds.length >= 4 ? tds[3].textContent.trim() : '';\n" +
                "    data.push({title: title, href: href, date: date, province: province});\n" +
                "  });\n" +
                "  return data;\n" +
                "}");

        if (result instanceof List<?> list) {
            for (Object obj : list) {
                try {
                    java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;
                    String title = String.valueOf(map.getOrDefault("title", ""));
                    String href = String.valueOf(map.getOrDefault("href", ""));
                    String dateText = String.valueOf(map.getOrDefault("date", ""));
                    String province = String.valueOf(map.getOrDefault("province", ""));

                    if (title.isBlank()) continue;

                    if (!href.isBlank() && !href.startsWith("http")) {
                        href = site.getBaseUrl() + (href.startsWith("/") ? "" : "/") + href;
                    }

                    CrawlItem item = new CrawlItem();
                    item.setTitle(title);
                    item.setSourceUrl(href.isBlank() ? null : href);
                    item.setPublishTime(dateText);
                    item.setCategory(category.getName());
                    item.setCategoryCode(category.getCode());
                    item.setSiteName(site.getName());
                    item.setProvince(province.isBlank() ? site.getProvince() : province);
                    item.setCity(site.getCity());

                    if (item.getSourceUrl() != null) {
                        item.setUrlHash(DigestUtils.md5Hex(item.getSourceUrl()));
                    } else {
                        item.setUrlHash(DigestUtils.md5Hex(title + dateText));
                    }

                    items.add(item);
                } catch (Exception e) {
                    log.debug("解析数据项失败: {}", e.getMessage());
                }
            }
        }

        return items;
    }

    /**
     * 爬取详情页
     * @param item 列表项（包含 sourceUrl）
     * @param site 站点配置
     * @param reqInterval 请求间隔
     */
    public void crawlDetail(CrawlItem item, CrawlerProperties.SiteConfig site, int reqInterval) {
        if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
            return;
        }

        String detailUrl = ensureAbsoluteUrl(item.getSourceUrl(), site.getBaseUrl());
        BrowserContext context = null;
        Page page = null;

        try {
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .setViewportSize(1920, 1080)
                    .setLocale("zh-CN");

            context = getBrowser(site.getPlaywrightConfig()).newContext(contextOptions);
            page = context.newPage();

            if (reqInterval > 0) {
                Thread.sleep(reqInterval * 1000L);
            }

            long timeoutMs = (site.getPlaywrightConfig() != null ? site.getPlaywrightConfig().getWaitTimeout() : 30) * 1000L;
            page.navigate(detailUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE)
                    .setTimeout(timeoutMs));

            // 等待页面渲染
            page.waitForTimeout(5000);

            // 提取内容
            CrawlerProperties.DetailSelectors sel = site.getDetailSelectors();
            String content = null;
            
            // 策略1：使用配置的 content 选择器
            if (sel.getContent() != null && !sel.getContent().isBlank()) {
                content = page.evaluate("() => {\n" +
                        "  const el = document.querySelector('" + sel.getContent().replace("'", "\\'") + "');\n" +
                        "  return el ? el.innerText : '';\n" +
                        "}").toString();
            }
            
            // 策略2：如果主选择器未找到，尝试备选选择器（表格容器等）
            if (content == null || content.isBlank()) {
                log.debug("主内容选择器 '{}' 未找到，尝试备选", sel.getContent());
                content = page.evaluate("() => {\n" +
                        "  // 尝试表格容器\n" +
                        "  let el = document.querySelector('table.public-table, table.form-table, div.portlet');\n" +
                        "  if (el) return el.innerText;\n" +
                        "  // 尝试主要内容区域\n" +
                        "  el = document.querySelector('div.detail-content, div.content, article');\n" +
                        "  if (el) return el.innerText;\n" +
                        "  // 最后使用 body\n" +
                        "  return document.body ? document.body.innerText : '';\n" +
                        "}").toString();
            }
            
            if (content != null && !content.isBlank()) {
                item.setDetail(content);
                item.setSummary(content.length() > 500 ? content.substring(0, 500) + "..." : content);
                log.debug("详情内容获取成功，长度: {}", content.length());
            } else {
                log.warn("详情内容为空: {}", detailUrl);
            }

            // 提取发布时间
            if (sel.getPublishTime() != null && !sel.getPublishTime().isBlank()) {
                String timeText = page.evaluate("() => {\n" +
                        "  const el = document.querySelector('" + sel.getPublishTime().replace("'", "\\'") + "');\n" +
                        "  if (!el) return '';\n" +
                        "  const attr = '" + (sel.getPublishTimeAttr() != null ? sel.getPublishTimeAttr().replace("attr:", "") : "") + "';\n" +
                        "  return attr ? (el.getAttribute(attr) || el.textContent) : el.textContent;\n" +
                        "}").toString();
                if (timeText != null && !timeText.isBlank()) {
                    item.setPublishTime(timeText.length() > 19 ? timeText.substring(0, 19) : timeText.trim());
                }
            }

            item.setSourceUrl(detailUrl);
            log.debug("详情获取成功: {}", item.getTitle());

        } catch (Exception e) {
            log.error("详情获取失败: {} - {}", detailUrl, e.getMessage());
        } finally {
            if (page != null) try { page.close(); } catch (Exception e) { /* ignore */ }
            if (context != null) try { context.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    private String ensureAbsoluteUrl(String url, String baseUrl) {
        if (url == null || url.isBlank()) return url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return baseUrl + url;
        return baseUrl + "/" + url;
    }
}
