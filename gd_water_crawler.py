"""广东省公共资源交易平台（水利工程）定时采集脚本。

职责仅限于抓取、解析、清洗和标准化输出：
- 不写业务数据库
- 不推送消息
- 固定同步入口：execute(context)
"""

from __future__ import annotations

import asyncio
import html
import re
import time
from typing import Any, Iterable
from urllib.parse import urlencode

import httpx


BASE_URL = "https://ygp.gdzwfw.gov.cn"
LIST_URL = BASE_URL + "/ggzy-portal/search/v2/items"
DETAIL_URL = BASE_URL + "/ggzy-portal/center/apis/trading-notice/new/detail"
NODE_URL = BASE_URL + "/ggzy-portal/center/apis/trading-notice/new/singleNode"
DEFAULT_TRADING_PROCESS_FILTER = "3C14,3C51,3C52,3C61"

# 交易环节 -> 公告类型。3161 是部分旧数据中的兼容写法。
TRADING_PROCESS_TYPE_MAP = {
    "3C11": "tender_notice",
    "3C12": "tender_notice",
    "3C13": "tender_notice",
    "3C14": "tender_notice",
    "3C51": "result_notice",
    "3C52": "result_notice",
    "3161": "contract_notice",
    "3C61": "contract_notice",
}

# 交易环节 -> 子类型。
TRADING_PROCESS_SUB_TYPE_MAP = {
    "3C11": "construction_tender",
    "3C12": "construction_tender",
    "3C13": "construction_tender",
    "3C14": "construction_tender",
    "3C51": "winning_notice",
    "3C52": "winning_notice",
    "3161": "engineering_contract",
    "3C61": "engineering_contract",
}

# 优先使用已逐条验证的“市级siteCode|bizCode”详情节点，避免重复触发
# singleNode 的高频反爬。这些组合均已验证标题、正文和 rawFields 非空。
# 如平台调整，可通过 context["nodeIdMap"] 精确覆盖；用户显式配置时也支持
# “*|bizCode”通配键，但内置配置不使用通配，避免把跨市兼容误当长期契约。
VERIFIED_NODE_ID_FALLBACK_MAP = {
    "440100|3C14": "2005939215280603137",
    "440100|3C51": "2005939215293186049",
    "440100|3C52": "2005939215326740481",
    "440200|3C14": "2005939215280603137",
    "440500|3C52": "2005939215326740481",
    "440500|3C61": "1982028456772669441",
    "440600|3C14": "2005939215280603137",
    "440900|3C14": "2005939215280603137",
    "440900|3C52": "2005939215326740481",
    "441400|3C14": "2005939215280603137",
    "441400|3C61": "1982028456772669441",
    "441500|3C14": "2005939215280603137",
    "441800|3C51": "2005939215293186049",
    "441900|3C14": "2005939215280603137",
    "445200|3C51": "2005939215293186049",
}

# 仅当精确组合未配置且 singleNode 请求失败时使用。它们已跨多个城市验证能返回
# 正确标题、正文和结构化字段，但仍不作为首选路径。
VERIFIED_GLOBAL_NODE_FALLBACK_MAP = {
    "*|3C14": "2005939215280603137",
    "*|3C51": "2005939215293186049",
    "*|3C52": "2005939215326740481",
    "*|3C61": "1982028456772669441",
}

RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504, 509}
ANTI_CRAWLER_ERRCODE = 90104


def _print_log(level: str, message: str, *args: Any) -> None:
    rendered = message % args if args else message
    print(f"[{level}] {rendered}", flush=True)


class CrawlerApiError(RuntimeError):
    """平台请求或业务响应异常。"""


class _RunBudgetExceeded(TimeoutError):
    """本次采集已耗尽允许的总运行时间。"""


class _RequestPacer:
    """保证相邻请求之间至少间隔指定秒数。"""

    def __init__(self, min_interval: float):
        self.min_interval = max(0.0, float(min_interval))
        self._lock = asyncio.Lock()
        self._last_request_at = 0.0

    async def wait(self) -> None:
        async with self._lock:
            now = time.monotonic()
            delay = self._last_request_at + self.min_interval - now
            if delay > 0:
                await asyncio.sleep(delay)
            self._last_request_at = time.monotonic()


class _RunBudget:
    def __init__(self, max_seconds: float):
        self.started_at = time.monotonic()
        self.max_seconds = max(0.01, float(max_seconds))
        self.deadline = self.started_at + self.max_seconds

    def elapsed(self) -> float:
        return max(0.0, time.monotonic() - self.started_at)

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())

    def expired(self) -> bool:
        return self.remaining() <= 0


async def _wait_with_budget(awaitable: Any, budget: _RunBudget | None) -> Any:
    if budget is None:
        return await awaitable

    remaining = budget.remaining()
    if remaining <= 0:
        if asyncio.iscoroutine(awaitable):
            awaitable.close()
        raise _RunBudgetExceeded("采集总时间预算已耗尽")

    try:
        return await asyncio.wait_for(awaitable, timeout=remaining)
    except asyncio.TimeoutError as exc:
        if budget.expired():
            raise _RunBudgetExceeded("采集总时间预算已耗尽") from exc
        raise


def _strip_html(text: Any) -> str:
    if text is None:
        return ""

    value = str(text)
    value = re.sub(r"(?i)<br\s*/?>", "\n", value)
    value = re.sub(r"(?i)</(?:p|div|li|tr|h[1-6])\s*>", "\n", value)
    value = re.sub(r"<[^>]+>", "", value)
    value = html.unescape(value).replace("\xa0", " ")

    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line).strip()


def _to_city_site_code(site_code: Any) -> str:
    """区县 code 转市级 code，例如 440515 -> 440500。"""

    value = str(site_code or "").strip()
    if len(value) >= 4:
        return value[:4] + "00"
    return value


def _extract_cells(richtext: str) -> list[str]:
    if not richtext:
        return []

    cells: list[str] = []
    pattern = r"<(?:td|th|li)\b[^>]*>(.*?)</(?:td|th|li)>"
    for match in re.finditer(pattern, richtext, flags=re.I | re.S):
        value = _strip_html(match.group(1))
        if value:
            cells.append(value)
    return cells


def _parse_raw_fields_from_html(richtext: str) -> dict[str, str]:
    """从富文本表格中尽量恢复中文字段名和值。"""

    cells = _extract_cells(richtext)
    raw: dict[str, str] = {}
    index = 0

    while index < len(cells):
        current = cells[index]

        inline = re.match(r"^([\u4e00-\u9fffA-Za-z0-9（）()/_-]{2,40})[:：]\s*(.+)$", current)
        if inline:
            key = inline.group(1).strip()
            value = inline.group(2).strip()
            if value:
                raw[key] = value
            index += 1
            continue

        if len(current) <= 40 and index + 1 < len(cells):
            next_value = cells[index + 1]
            if next_value and next_value != current:
                raw.setdefault(current, next_value)
                index += 2
                continue

        index += 1

    return raw


def _iter_key_value_entries(value: Any) -> Iterable[dict[str, Any]]:
    """兼容 multiKeyValueTableList 的多层数组结构。"""

    if isinstance(value, dict):
        yield value
        return

    if isinstance(value, list):
        for item in value:
            yield from _iter_key_value_entries(item)


def _parse_detail_fields(detail_payload: dict[str, Any]) -> dict[str, Any]:
    data = detail_payload.get("data") or {}
    columns = data.get("tradingNoticeColumnModelList") or []

    richtexts: list[str] = []
    raw_fields: dict[str, str] = {}
    fields_by_code: dict[str, str] = {}

    for column in columns:
        if not isinstance(column, dict):
            continue

        richtext = column.get("richtext")
        if isinstance(richtext, str) and richtext.strip():
            richtexts.append(richtext.strip())

        table = column.get("multiKeyValueTableList") or []
        for entry in _iter_key_value_entries(table):
            value = _strip_html(entry.get("value"))
            if not value or value.lower() == "invalid date":
                continue

            code = str(entry.get("code") or "").strip()
            label = str(entry.get("key") or entry.get("name") or code).strip()
            if code:
                fields_by_code[code] = value
            if label:
                raw_fields[label] = value

    return {
        "_richtext": "\n".join(richtexts),
        "_raw_fields": raw_fields,
        "_fields_by_code": fields_by_code,
    }


def _first_nonempty(*values: Any) -> str:
    for value in values:
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _build_browser_url(item: dict[str, Any]) -> str:
    notice_id = str(item.get("noticeId") or "")
    edition = str(item.get("edition") or "v3")
    biz_code = str(item.get("tradingProcess") or "")
    site_code = _to_city_site_code(item.get("siteCode"))

    query = {
        "noticeId": notice_id,
        "projectCode": str(item.get("projectCode") or ""),
        "bizCode": biz_code,
        "siteCode": site_code,
        "publishDate": str(item.get("publishDate") or ""),
        "source": str(item.get("pubServicePlat") or ""),
        "titleDetails": "工程建设",
        "classify": "A07",
    }
    return f"{BASE_URL}/#/44/new/jygg/{edition}/A?{urlencode(query)}"


def _map_fields(item: dict[str, Any], detail_fields: dict[str, Any]) -> dict[str, Any]:
    trading_process = str(item.get("tradingProcess") or "")
    notice_type = TRADING_PROCESS_TYPE_MAP.get(trading_process, "other_notice")
    sub_type = TRADING_PROCESS_SUB_TYPE_MAP.get(trading_process, "")

    richtext = str(detail_fields.get("_richtext") or "")
    fields_by_code = detail_fields.get("_fields_by_code") or {}

    raw_fields = dict(detail_fields.get("_raw_fields") or {})
    for key, value in _parse_raw_fields_from_html(richtext).items():
        raw_fields.setdefault(key, value)

    project_name = _first_nonempty(
        fields_by_code.get("TENDER_PROJECT_NAME"),
        fields_by_code.get("PROJECT_NAME"),
        fields_by_code.get("CONTRACT_NAME"),
        item.get("noticeTitle"),
    )
    tenderer_name = _first_nonempty(
        fields_by_code.get("TENDERER_NAME"),
        fields_by_code.get("PURCHASER_NAME"),
        fields_by_code.get("CONTRACT_PARTY_A"),
        item.get("projectOwner"),
    )

    # noticeId 必须完整保留，不能裁剪 siteCode/bizCode 前缀。
    notice_id = str(item.get("noticeId") or item.get("docId") or "")

    return {
        "externalId": notice_id,
        "title": str(item.get("noticeTitle") or ""),
        "detailUrl": _build_browser_url(item),
        "publishTime": str(item.get("publishDate") or ""),
        "region": str(item.get("regionName") or ""),
        "industry": str(item.get("projectTypeName") or ""),
        "projectName": project_name,
        "projectCode": str(item.get("projectCode") or ""),
        "tendererName": tenderer_name,
        "noticeType": notice_type,
        "subType": sub_type,
        "noticeContent": _strip_html(richtext),
        "rawFields": raw_fields,
    }


def _short_response_body(response: httpx.Response, limit: int = 500) -> str:
    body = response.text.strip().replace("\r", " ").replace("\n", " ")
    return body if len(body) <= limit else body[:limit] + "..."


async def _request_with_retry(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    operation: str,
    pacer: _RequestPacer,
    max_attempts: int,
    retry_base_delay: float,
    network_semaphore: asyncio.Semaphore | None = None,
    budget: _RunBudget | None = None,
    request_wall_timeout: float | None = None,
    **kwargs: Any,
) -> httpx.Response:
    attempts = max(1, int(max_attempts))
    last_error: Exception | None = None

    for attempt in range(1, attempts + 1):
        try:
            async def send_request() -> httpx.Response:
                await _wait_with_budget(pacer.wait(), budget)
                request_awaitable = client.request(method, url, **kwargs)
                if request_wall_timeout is not None:
                    request_awaitable = asyncio.wait_for(
                        request_awaitable,
                        timeout=max(0.01, request_wall_timeout),
                    )
                return await _wait_with_budget(
                    request_awaitable,
                    budget,
                )

            if network_semaphore is None:
                response = await send_request()
            else:
                async with network_semaphore:
                    response = await send_request()
        except _RunBudgetExceeded:
            raise
        except (httpx.RequestError, asyncio.TimeoutError) as exc:
            last_error = exc
            if attempt >= attempts:
                break
            delay = max(0.0, retry_base_delay) * (2 ** (attempt - 1))
            _print_log(
                "WARN",
                "%s 网络异常，第 %s/%s 次：%s；%.1f 秒后重试",
                operation,
                attempt,
                attempts,
                exc,
                delay,
            )
            if delay:
                await _wait_with_budget(asyncio.sleep(delay), budget)
            continue

        anti_crawler = False
        try:
            payload = response.json()
            anti_crawler = (
                isinstance(payload, dict)
                and str(payload.get("errcode")) == str(ANTI_CRAWLER_ERRCODE)
            )
        except (ValueError, TypeError):
            pass

        retryable = response.status_code in RETRYABLE_STATUS_CODES or anti_crawler
        if retryable and attempt < attempts:
            delay = max(0.0, retry_base_delay) * (2 ** (attempt - 1))
            _print_log(
                "WARN",
                "%s 被限流或服务异常，第 %s/%s 次，HTTP=%s，响应=%s；%.1f 秒后重试",
                operation,
                attempt,
                attempts,
                response.status_code,
                _short_response_body(response),
                delay,
            )
            if delay:
                await _wait_with_budget(asyncio.sleep(delay), budget)
            continue

        if response.status_code >= 400:
            raise CrawlerApiError(
                f"{operation} HTTP {response.status_code}: {_short_response_body(response)}"
            )

        if anti_crawler:
            raise CrawlerApiError(
                f"{operation} 触发平台反爬: {_short_response_body(response)}"
            )

        return response

    raise CrawlerApiError(f"{operation} 请求失败，已尝试 {attempts} 次: {last_error}")


def _require_success_payload(response: httpx.Response, operation: str) -> dict[str, Any]:
    try:
        payload = response.json()
    except ValueError as exc:
        raise CrawlerApiError(
            f"{operation} 返回的不是 JSON: {_short_response_body(response)}"
        ) from exc

    if not isinstance(payload, dict):
        raise CrawlerApiError(f"{operation} 返回格式异常: {type(payload).__name__}")

    errcode = payload.get("errcode")
    if errcode not in (None, 0, "0"):
        errmsg = payload.get("errmsg") or "未知业务错误"
        raise CrawlerApiError(f"{operation} 失败，errcode={errcode}: {errmsg}")

    return payload


async def _fetch_list(
    client: httpx.AsyncClient,
    *,
    site_code: str,
    second_type: str,
    project_type: str,
    trading_process: str,
    page_no: int,
    page_size: int,
    pacer: _RequestPacer,
    max_attempts: int,
    retry_base_delay: float,
    network_semaphore: asyncio.Semaphore | None = None,
    budget: _RunBudget | None = None,
    request_wall_timeout: float | None = None,
) -> list[dict[str, Any]]:
    payload = {
        "type": "trading-type",
        "openConvert": False,
        "keyword": "",
        "siteCode": site_code,
        "secondType": second_type,
        "tradingProcess": trading_process,
        "thirdType": "[]",
        "projectType": project_type,
        "publishStartTime": "",
        "publishEndTime": "",
        "pageNo": page_no,
        "pageSize": page_size,
    }
    response = await _request_with_retry(
        client,
        "POST",
        LIST_URL,
        operation=f"列表接口 page={page_no}",
        pacer=pacer,
        max_attempts=max_attempts,
        retry_base_delay=retry_base_delay,
        network_semaphore=network_semaphore,
        budget=budget,
        request_wall_timeout=request_wall_timeout,
        json=payload,
    )
    body = _require_success_payload(response, f"列表接口 page={page_no}")
    data = body.get("data") or {}
    records = data.get("pageData") or []
    return [record for record in records if isinstance(record, dict)]


async def _fetch_node_id(
    client: httpx.AsyncClient,
    *,
    site_code: str,
    biz_code: str,
    pacer: _RequestPacer,
    max_attempts: int,
    retry_base_delay: float,
    network_semaphore: asyncio.Semaphore | None = None,
    budget: _RunBudget | None = None,
    request_wall_timeout: float | None = None,
) -> str:
    city_site_code = _to_city_site_code(site_code)
    params = {
        "siteCode": city_site_code,
        "tradingType": "A",
        "bizCode": biz_code,
        "classify": "A07",
    }
    operation = f"singleNode siteCode={city_site_code}, bizCode={biz_code}"
    response = await _request_with_retry(
        client,
        "GET",
        NODE_URL,
        operation=operation,
        pacer=pacer,
        max_attempts=max_attempts,
        retry_base_delay=retry_base_delay,
        network_semaphore=network_semaphore,
        budget=budget,
        request_wall_timeout=request_wall_timeout,
        params=params,
    )

    text = response.text.strip().strip('"')
    if text.isdigit():
        return text

    payload = _require_success_payload(response, operation)
    node_id = payload.get("data")
    normalized_node_id = str(node_id or "").strip().strip('"')
    if not normalized_node_id.isdigit():
        raise CrawlerApiError(
            f"{operation} 未返回有效数字 nodeId: {_short_response_body(response)}"
        )
    return normalized_node_id


async def _resolve_node_id(
    client: httpx.AsyncClient,
    *,
    site_code: str,
    biz_code: str,
    fallback_map: dict[str, str],
    pacer: _RequestPacer,
    max_attempts: int,
    retry_base_delay: float,
    network_semaphore: asyncio.Semaphore | None = None,
    budget: _RunBudget | None = None,
    request_wall_timeout: float | None = None,
) -> tuple[str, bool]:
    """动态查询 nodeId；查询失败时使用已验证的站点级兜底值。"""

    city_site_code = _to_city_site_code(site_code)
    try:
        node_id = await _fetch_node_id(
            client,
            site_code=city_site_code,
            biz_code=biz_code,
            pacer=pacer,
            max_attempts=max_attempts,
            retry_base_delay=retry_base_delay,
            network_semaphore=network_semaphore,
            budget=budget,
            request_wall_timeout=request_wall_timeout,
        )
        return node_id, False
    except CrawlerApiError:
        fallback_key = f"{city_site_code}|{biz_code}"
        global_fallback_key = f"*|{biz_code}"
        selected_key = (
            fallback_key
            if fallback_map.get(fallback_key)
            else global_fallback_key
        )
        fallback = str(fallback_map.get(selected_key) or "").strip()
        if not fallback:
            raise
        _print_log(
            "WARN",
            "singleNode 查询失败，使用已验证兜底 nodeId：%s -> %s",
            selected_key,
            fallback,
        )
        return fallback, True


async def _fetch_detail(
    client: httpx.AsyncClient,
    *,
    notice_id: str,
    project_code: str,
    site_code: str,
    biz_code: str,
    node_id: str,
    version: str,
    pacer: _RequestPacer,
    max_attempts: int,
    retry_base_delay: float,
    network_semaphore: asyncio.Semaphore | None = None,
    budget: _RunBudget | None = None,
    request_wall_timeout: float | None = None,
) -> dict[str, Any]:
    normalized_node_id = str(node_id or "").strip().strip('"')
    if not normalized_node_id.isdigit():
        raise CrawlerApiError(
            f"详情接口拒绝发送：nodeId 为空或不是数字，bizCode={biz_code}"
        )

    params = {
        "version": version or "v3",
        "tradingType": "A",
        # 原始 noticeId 必须完整透传，不能删除 siteCode/bizCode 前缀。
        "noticeId": notice_id,
        "bizCode": biz_code,
        "projectCode": project_code,
        "siteCode": _to_city_site_code(site_code),
        "nodeId": normalized_node_id,
    }

    operation = f"详情接口 noticeId={notice_id}"
    response = await _request_with_retry(
        client,
        "GET",
        DETAIL_URL,
        operation=operation,
        pacer=pacer,
        max_attempts=max_attempts,
        retry_base_delay=retry_base_delay,
        network_semaphore=network_semaphore,
        budget=budget,
        request_wall_timeout=request_wall_timeout,
        params=params,
    )
    return _require_success_payload(response, operation)


def _context_int(context: dict[str, Any], key: str, default: int) -> int:
    try:
        return int(context.get(key, default))
    except (TypeError, ValueError):
        return default


def _context_float(context: dict[str, Any], key: str, default: float) -> float:
    try:
        return float(context.get(key, default))
    except (TypeError, ValueError):
        return default


async def _collect(context: dict[str, Any]) -> dict[str, Any]:
    site_code = str(context.get("siteCode") or "44")
    second_type = str(context.get("secondType") or "A")
    project_type = str(context.get("projectType") or "A07")
    trading_process = str(
        context.get("tradingProcess") or DEFAULT_TRADING_PROCESS_FILTER
    )
    max_pages = min(1, max(1, _context_int(context, "maxPages", 1)))
    page_size = min(10, max(1, _context_int(context, "pageSize", 10)))
    max_items = min(10, max(1, _context_int(context, "maxItems", 10)))
    max_attempts = min(2, max(1, _context_int(context, "maxAttempts", 1)))
    request_interval = max(0.0, _context_float(context, "requestInterval", 1.5))
    retry_base_delay = max(0.0, _context_float(context, "retryBaseDelay", 0.8))
    request_timeout = min(10.0, max(0.1, _context_float(context, "requestTimeout", 8.0)))
    connect_timeout = min(
        request_timeout,
        max(0.1, _context_float(context, "connectTimeout", 3.0)),
    )
    # 宿主通常在 60 秒强杀；最多使用 45 秒，给任务取消和连接关闭留足余量。
    max_run_seconds = min(45.0, max(0.01, _context_float(context, "maxRunSeconds", 45.0)))
    max_concurrency = min(3, max(1, _context_int(context, "maxConcurrency", 1)))
    verify_ssl = context.get("verifySsl", True) is not False
    budget = _RunBudget(max_run_seconds)

    node_id_fallback_map = dict(VERIFIED_NODE_ID_FALLBACK_MAP)
    node_id_fallback_map.update(VERIFIED_GLOBAL_NODE_FALLBACK_MAP)
    context_node_map = context.get("nodeIdMap")
    context_node_overrides: dict[str, str] = {}
    if isinstance(context_node_map, dict):
        context_node_overrides = {
            str(key): str(value)
            for key, value in context_node_map.items()
            if value is not None and str(value).strip()
        }
        node_id_fallback_map.update(context_node_overrides)

    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/131.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Content-Type": "application/json;charset=UTF-8",
        "Origin": BASE_URL,
        "Referer": BASE_URL + "/",
    }

    records: list[dict[str, Any]] = []
    error_samples: list[str] = []
    pacer = _RequestPacer(request_interval)
    network_semaphore = asyncio.Semaphore(max_concurrency)

    pages_fetched = 0
    detail_succeeded = 0
    detail_failed = 0
    node_failed = 0
    node_fallback_used = 0
    timed_out = False
    stop_reason = ""
    partial_list = False

    def remember_error(stage: str, error: Any) -> None:
        message = f"{stage}: {error}"
        if len(error_samples) < 5 and message not in error_samples:
            error_samples.append(message[:500])

    _print_log(
        "INFO",
        "采集开始：maxPages=%s, pageSize=%s, maxItems=%s, concurrency=%s, "
        "requestTimeout=%.1fs, maxAttempts=%s, budget=%.1fs",
        max_pages,
        page_size,
        max_items,
        max_concurrency,
        request_timeout,
        max_attempts,
        max_run_seconds,
    )

    async with httpx.AsyncClient(
        timeout=httpx.Timeout(
            connect=connect_timeout,
            read=request_timeout,
            write=request_timeout,
            pool=connect_timeout,
        ),
        headers=headers,
        follow_redirects=True,
        verify=verify_ssl,
    ) as client:
        for page_no in range(1, max_pages + 1):
            if len(records) >= max_items:
                break
            if budget.expired():
                timed_out = True
                stop_reason = "time_budget_exceeded"
                break

            _print_log("INFO", "开始获取列表第 %s/%s 页", page_no, max_pages)
            try:
                page_records = await _fetch_list(
                    client,
                    site_code=site_code,
                    second_type=second_type,
                    project_type=project_type,
                    trading_process=trading_process,
                    page_no=page_no,
                    page_size=page_size,
                    pacer=pacer,
                    max_attempts=max_attempts,
                    retry_base_delay=retry_base_delay,
                    network_semaphore=network_semaphore,
                    budget=budget,
                    request_wall_timeout=request_timeout,
                )
            except _RunBudgetExceeded:
                timed_out = True
                stop_reason = "time_budget_exceeded"
                _print_log("WARN", "列表阶段耗尽总时间预算")
                break
            except CrawlerApiError as exc:
                remember_error(f"list.page.{page_no}", exc)
                if not records:
                    raise
                partial_list = True
                stop_reason = "list_partial_failure"
                _print_log("ERROR", "第 %s 页列表失败，继续处理已获取记录：%s", page_no, exc)
                break

            pages_fetched += 1
            remaining_slots = max_items - len(records)
            records.extend(page_records[:remaining_slots])
            _print_log(
                "INFO",
                "列表第 %s 页完成：本页=%s，累计=%s/%s",
                page_no,
                len(page_records),
                len(records),
                max_items,
            )

            if len(page_records) < page_size:
                break

        result_slots: list[dict[str, Any] | None] = [None] * len(records)
        record_semaphore = asyncio.Semaphore(max_concurrency)
        node_tasks: dict[
            tuple[str, str],
            asyncio.Task[tuple[str, bool]],
        ] = {}

        async def resolve_node(city_site_code: str, biz_code: str) -> tuple[str, bool]:
            nonlocal node_fallback_used
            cache_key = (city_site_code, biz_code)
            existing = node_tasks.get(cache_key)
            if existing is not None:
                return await asyncio.shield(existing)

            async def load_node() -> tuple[str, bool]:
                nonlocal node_fallback_used
                fallback_key = f"{city_site_code}|{biz_code}"
                global_fallback_key = f"*|{biz_code}"
                if context_node_overrides.get(fallback_key):
                    configured_key = fallback_key
                    configured = context_node_overrides[fallback_key]
                elif context_node_overrides.get(global_fallback_key):
                    configured_key = global_fallback_key
                    configured = context_node_overrides[global_fallback_key]
                elif node_id_fallback_map.get(fallback_key):
                    configured_key = fallback_key
                    configured = node_id_fallback_map[fallback_key]
                else:
                    configured_key = ""
                    configured = ""
                configured = str(configured).strip()
                if configured:
                    node_fallback_used += 1
                    _print_log("INFO", "使用预置 nodeId：%s -> %s", configured_key, configured)
                    return configured, True

                resolved = await _resolve_node_id(
                    client,
                    site_code=city_site_code,
                    biz_code=biz_code,
                    fallback_map=node_id_fallback_map,
                    pacer=pacer,
                    max_attempts=max_attempts,
                    retry_base_delay=retry_base_delay,
                    network_semaphore=network_semaphore,
                    budget=budget,
                    request_wall_timeout=request_timeout,
                )
                _print_log("INFO", "nodeId 获取成功：%s|%s", city_site_code, biz_code)
                return resolved

            task = asyncio.create_task(load_node())
            node_tasks[cache_key] = task
            return await asyncio.shield(task)

        async def process_record_inner(index: int, record: dict[str, Any]) -> None:
            nonlocal detail_succeeded, detail_failed, node_failed

            notice_id = str(record.get("noticeId") or "")
            if not notice_id:
                detail_failed += 1
                message = f"记录 {index + 1} 缺少 noticeId"
                remember_error("record", message)
                _print_log("WARN", "%s", message)
                return

            project_code = str(record.get("projectCode") or "")
            item_site_code = str(record.get("siteCode") or site_code)
            city_site_code = _to_city_site_code(item_site_code)
            biz_code = str(record.get("tradingProcess") or "")
            version = str(record.get("edition") or "v3")

            try:
                node_id, _ = await resolve_node(city_site_code, biz_code)
            except _RunBudgetExceeded:
                raise
            except CrawlerApiError as exc:
                node_failed += 1
                remember_error("node", exc)
                _print_log(
                    "ERROR",
                    "[%s/%s] nodeId 获取失败：%s",
                    index + 1,
                    len(records),
                    exc,
                )
                return

            try:
                detail_payload = await _fetch_detail(
                    client,
                    notice_id=notice_id,
                    project_code=project_code,
                    site_code=city_site_code,
                    biz_code=biz_code,
                    node_id=node_id,
                    version=version,
                    pacer=pacer,
                    max_attempts=max_attempts,
                    retry_base_delay=retry_base_delay,
                    network_semaphore=network_semaphore,
                    budget=budget,
                    request_wall_timeout=request_timeout,
                )
                detail_fields = _parse_detail_fields(detail_payload)
                if not detail_fields.get("_richtext") and not detail_fields.get("_raw_fields"):
                    raise CrawlerApiError("详情接口返回空内容")
            except _RunBudgetExceeded:
                raise
            except CrawlerApiError as exc:
                detail_failed += 1
                remember_error("detail", exc)
                _print_log(
                    "ERROR",
                    "[%s/%s] 详情获取失败：%s",
                    index + 1,
                    len(records),
                    exc,
                )
                return

            result_slots[index] = _map_fields(record, detail_fields)
            detail_succeeded += 1
            _print_log(
                "INFO",
                "[%s/%s] 详情完成：%s",
                index + 1,
                len(records),
                record.get("noticeTitle") or notice_id,
            )

        async def process_record(index: int, record: dict[str, Any]) -> None:
            async with record_semaphore:
                await process_record_inner(index, record)

        record_tasks = [
            asyncio.create_task(process_record(index, record))
            for index, record in enumerate(records)
        ]

        if record_tasks:
            remaining = budget.remaining()
            if remaining <= 0:
                done: set[asyncio.Task[Any]] = set()
                pending = set(record_tasks)
            else:
                done, pending = await asyncio.wait(record_tasks, timeout=remaining)

            if pending:
                timed_out = True
                stop_reason = "time_budget_exceeded"
                _print_log(
                    "WARN",
                    "总时间预算耗尽，取消 %s 个未完成任务并返回已成功结果",
                    len(pending),
                )
                for task in pending:
                    task.cancel()

            outcomes = await asyncio.gather(*record_tasks, return_exceptions=True)
            for outcome in outcomes:
                if isinstance(outcome, _RunBudgetExceeded):
                    timed_out = True
                    stop_reason = "time_budget_exceeded"
                elif isinstance(outcome, Exception):
                    raise outcome

            unfinished_node_tasks = [task for task in node_tasks.values() if not task.done()]
            for task in unfinished_node_tasks:
                task.cancel()
            if node_tasks:
                await asyncio.gather(*node_tasks.values(), return_exceptions=True)

    items = [item for item in result_slots if item is not None]
    has_item_failures = detail_failed > 0 or node_failed > 0
    partial = timed_out or partial_list or has_item_failures or len(items) < len(records)
    if not stop_reason and has_item_failures:
        stop_reason = "item_failures"
    status = "partial" if partial else "success"
    elapsed_seconds = round(budget.elapsed(), 3)

    _print_log(
        "INFO",
        "采集结束：status=%s, fetched=%s, accepted=%s, elapsed=%.3fs, stopReason=%s",
        status,
        len(records),
        len(items),
        elapsed_seconds,
        stop_reason or "completed",
    )

    return {
        "sourceCode": context.get("sourceCode", ""),
        "runId": context.get("runId", ""),
        "items": items,
        "stats": {
            "status": status,
            "partial": partial,
            "fetched": len(records),
            "accepted": len(items),
            "pages": pages_fetched,
            "projectType": project_type,
            "detailSucceeded": detail_succeeded,
            "detailFailed": detail_failed,
            "nodeFailed": node_failed,
            "nodeFallbackUsed": node_fallback_used,
            "timedOut": timed_out,
            "stopReason": stop_reason,
            "elapsedSeconds": elapsed_seconds,
            "errorSamples": error_samples,
        },
    }


def execute(context: dict[str, Any] | None) -> dict[str, Any]:
    """定时任务固定入口。"""

    _print_log("INFO", "execute(context) 已进入")
    try:
        return asyncio.run(_collect(context or {}))
    except Exception as exc:
        _print_log("FATAL", "%s: %s", type(exc).__name__, exc)
        raise
