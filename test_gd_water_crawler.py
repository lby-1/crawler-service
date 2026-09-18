import asyncio
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx

import gd_water_crawler as crawler


class SourceContractTests(unittest.TestCase):
    def setUp(self):
        self.source = Path(crawler.__file__).read_text(encoding="utf-8")

    def test_runtime_script_does_not_import_or_configure_logging(self):
        self.assertNotIn("import logging", self.source)
        self.assertNotIn("logging.", self.source)
        self.assertNotIn("LOGGER", self.source)

    def test_runtime_script_has_no_main_execution_block(self):
        self.assertNotIn('if __name__ == "__main__":', self.source)

    def test_runtime_script_does_not_use_the_old_thirty_second_timeout(self):
        self.assertNotIn("httpx.Timeout(30.0)", self.source)
        self.assertIn('"requestTimeout"', self.source)

    def test_print_logs_are_flushed_immediately(self):
        with patch("builtins.print") as print_mock:
            crawler._print_log("INFO", "page=%s", 1)

        print_mock.assert_called_once_with("[INFO] page=1", flush=True)


class MappingTests(unittest.TestCase):
    def test_builtin_node_map_contains_only_verified_city_process_pairs(self):
        self.assertFalse(
            any(key.startswith("*|") for key in crawler.VERIFIED_NODE_ID_FALLBACK_MAP)
        )
        self.assertEqual(
            crawler.VERIFIED_NODE_ID_FALLBACK_MAP["445200|3C51"],
            "2005939215293186049",
        )
        self.assertEqual(
            crawler.VERIFIED_NODE_ID_FALLBACK_MAP["441400|3C61"],
            "1982028456772669441",
        )

    def test_all_configured_tender_process_codes_are_classified_as_tenders(self):
        for process_code in ("3C11", "3C12", "3C13", "3C14"):
            with self.subTest(process_code=process_code):
                self.assertEqual(
                    crawler.TRADING_PROCESS_TYPE_MAP[process_code],
                    "tender_notice",
                )
                self.assertEqual(
                    crawler.TRADING_PROCESS_SUB_TYPE_MAP[process_code],
                    "construction_tender",
                )

    def test_contract_process_codes_are_classified_as_contracts(self):
        for process_code in ("3161", "3C61"):
            with self.subTest(process_code=process_code):
                self.assertEqual(
                    crawler.TRADING_PROCESS_TYPE_MAP[process_code],
                    "contract_notice",
                )
                self.assertEqual(
                    crawler.TRADING_PROCESS_SUB_TYPE_MAP[process_code],
                    "engineering_contract",
                )

    def test_county_site_code_is_normalized_to_city_code(self):
        self.assertEqual(crawler._to_city_site_code("440515"), "440500")
        self.assertEqual(crawler._to_city_site_code("44"), "44")

    def test_mapping_keeps_the_complete_contract_notice_id(self):
        notice_id = "4405153C61fa2fdedc1df6410f9f58379a1261d538"
        item = {
            "noticeId": notice_id,
            "noticeTitle": "测试合同",
            "publishDate": "20260910103615",
            "regionName": "汕头市",
            "projectTypeName": "水利",
            "projectCode": "N4405151301000020003",
            "projectOwner": "测试单位",
            "tradingProcess": "3C61",
            "siteCode": "440515",
            "edition": "v3",
        }

        mapped = crawler._map_fields(
            item,
            {
                "_richtext": "<p>合同正文</p>",
                "_raw_fields": {"合同名称": "测试合同"},
            },
        )

        self.assertEqual(mapped["externalId"], notice_id)
        self.assertEqual(mapped["noticeType"], "contract_notice")
        self.assertEqual(mapped["subType"], "engineering_contract")
        self.assertEqual(mapped["noticeContent"], "合同正文")
        self.assertEqual(mapped["rawFields"]["合同名称"], "测试合同")

    def test_detail_parser_collects_structured_fields_and_richtext(self):
        payload = {
            "errcode": 0,
            "data": {
                "tradingNoticeColumnModelList": [
                    {
                        "richtext": "<p>第一段</p>",
                        "multiKeyValueTableList": [
                            [
                                {
                                    "code": "CONTRACT_NAME",
                                    "key": "合同名称",
                                    "value": "示例合同",
                                }
                            ]
                        ],
                    },
                    {"richtext": "<p>第二段</p>"},
                ]
            },
        }

        parsed = crawler._parse_detail_fields(payload)

        self.assertEqual(parsed["_richtext"], "<p>第一段</p>\n<p>第二段</p>")
        self.assertEqual(parsed["_raw_fields"]["合同名称"], "示例合同")


class RequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_node_lookup_rejects_non_numeric_data(self):
        def handler(request):
            return httpx.Response(
                200,
                json={
                    "errcode": 0,
                    "errmsg": "ok",
                    "data": {"type": "ANTI-CRAWLER"},
                },
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(crawler.CrawlerApiError, "nodeId"):
                await crawler._fetch_node_id(
                    client,
                    site_code="441500",
                    biz_code="3C14",
                    pacer=crawler._RequestPacer(0),
                    max_attempts=1,
                    retry_base_delay=0,
                )

    async def test_detail_rejects_missing_node_id_before_sending_request(self):
        requests_sent = 0

        def handler(request):
            nonlocal requests_sent
            requests_sent += 1
            return httpx.Response(200, json={"errcode": 0, "data": {}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(crawler.CrawlerApiError, "nodeId"):
                await crawler._fetch_detail(
                    client,
                    notice_id="notice",
                    project_code="project",
                    site_code="441500",
                    biz_code="3C14",
                    node_id=None,
                    version="v3",
                    pacer=crawler._RequestPacer(0),
                    max_attempts=1,
                    retry_base_delay=0,
                )

        self.assertEqual(requests_sent, 0)

    async def test_detail_always_sends_validated_node_id(self):
        sent_params = {}

        def handler(request):
            sent_params.update(request.url.params.multi_items())
            return httpx.Response(200, json={"errcode": 0, "data": {}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            await crawler._fetch_detail(
                client,
                notice_id="notice",
                project_code="project",
                site_code="441500",
                biz_code="3C14",
                node_id="2005939215280603137",
                version="v3",
                pacer=crawler._RequestPacer(0),
                max_attempts=1,
                retry_base_delay=0,
            )

        self.assertEqual(sent_params["nodeId"], "2005939215280603137")

    async def test_node_lookup_retries_509_and_returns_plain_text_id(self):
        attempts = 0

        def handler(request):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                return httpx.Response(
                    509,
                    json={"errcode": 90104, "errmsg": "当前访问过于频繁"},
                )
            return httpx.Response(200, text="1982028456772669441")

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            node_id = await crawler._fetch_node_id(
                client,
                site_code="440515",
                biz_code="3C61",
                pacer=crawler._RequestPacer(0),
                max_attempts=2,
                retry_base_delay=0,
            )

        self.assertEqual(node_id, "1982028456772669441")
        self.assertEqual(attempts, 2)

    async def test_node_lookup_uses_verified_fallback_after_anti_crawler_failure(self):
        def handler(request):
            return httpx.Response(
                509,
                json={"errcode": 90104, "errmsg": "当前访问过于频繁"},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            node_id, used_fallback = await crawler._resolve_node_id(
                client,
                site_code="440515",
                biz_code="3C61",
                fallback_map={"440500|3C61": "1982028456772669441"},
                pacer=crawler._RequestPacer(0),
                max_attempts=1,
                retry_base_delay=0,
            )

        self.assertEqual(node_id, "1982028456772669441")
        self.assertTrue(used_fallback)

    async def test_node_lookup_uses_global_biz_fallback_only_after_request_failure(self):
        attempts = 0

        def handler(request):
            nonlocal attempts
            attempts += 1
            return httpx.Response(
                509,
                json={"errcode": 90104, "errmsg": "当前访问过于频繁"},
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            node_id, used_fallback = await crawler._resolve_node_id(
                client,
                site_code="441400",
                biz_code="3C61",
                fallback_map={"*|3C61": "1982028456772669441"},
                pacer=crawler._RequestPacer(0),
                max_attempts=1,
                retry_base_delay=0,
            )

        self.assertEqual(attempts, 1)
        self.assertEqual(node_id, "1982028456772669441")
        self.assertTrue(used_fallback)

    async def test_string_anti_crawler_errcode_is_retried(self):
        attempts = 0

        def handler(request):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                return httpx.Response(
                    200,
                    json={"errcode": "90104", "errmsg": "当前访问过于频繁"},
                )
            return httpx.Response(200, text="1982028456772669441")

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            node_id = await crawler._fetch_node_id(
                client,
                site_code="440515",
                biz_code="3C61",
                pacer=crawler._RequestPacer(0),
                max_attempts=2,
                retry_base_delay=0,
            )

        self.assertEqual(node_id, "1982028456772669441")
        self.assertEqual(attempts, 2)

    async def test_detail_business_error_is_not_silently_converted_to_empty_data(self):
        def handler(request):
            return httpx.Response(
                200,
                json={
                    "errcode": 10300,
                    "errmsg": "交易环节id不能为空",
                    "data": None,
                },
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with self.assertRaisesRegex(crawler.CrawlerApiError, "交易环节id不能为空"):
                await crawler._fetch_detail(
                    client,
                    notice_id="sample",
                    project_code="project",
                    site_code="440515",
                    biz_code="3C61",
                    node_id="123456789",
                    version="v3",
                    pacer=crawler._RequestPacer(0),
                    max_attempts=1,
                    retry_base_delay=0,
                )

    async def test_collect_does_not_accept_record_when_node_lookup_fails(self):
        record = {
            "noticeId": "sample-notice",
            "noticeTitle": "示例公告",
            "projectCode": "sample-project",
            "siteCode": "445200",
            "tradingProcess": "3C99",
            "edition": "v3",
        }

        with (
            patch.object(
                crawler,
                "_fetch_list",
                AsyncMock(return_value=[record]),
            ),
            patch.object(
                crawler,
                "_resolve_node_id",
                AsyncMock(side_effect=crawler.CrawlerApiError("nodeId lookup failed")),
            ),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 1,
                    "pageSize": 2,
                    "requestInterval": 0,
                }
            )

        self.assertEqual(result["items"], [])
        self.assertEqual(result["stats"]["fetched"], 1)
        self.assertEqual(result["stats"]["accepted"], 0)
        self.assertEqual(result["stats"]["nodeFailed"], 1)

    async def test_collect_propagates_list_failure_instead_of_returning_empty_success(self):
        with patch.object(
            crawler,
            "_fetch_list",
            AsyncMock(side_effect=crawler.CrawlerApiError("list unavailable")),
        ):
            with self.assertRaisesRegex(crawler.CrawlerApiError, "list unavailable"):
                await crawler._collect(
                    {
                        "maxPages": 1,
                        "pageSize": 1,
                        "requestInterval": 0,
                    }
                )


class SamplingRuntimeTests(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def _record(index):
        return {
            "noticeId": f"notice-{index}",
            "noticeTitle": f"示例公告{index}",
            "projectCode": f"project-{index}",
            "siteCode": "440515",
            "tradingProcess": "3C61",
            "edition": "v3",
        }

    async def test_collect_caps_output_at_ten_items(self):
        pages = [
            [self._record(i) for i in range(0, 10)],
            [self._record(i) for i in range(10, 20)],
            [self._record(i) for i in range(20, 30)],
        ]
        fetch_list = AsyncMock(side_effect=pages)
        detail_payload = {
            "errcode": 0,
            "data": {
                "tradingNoticeColumnModelList": [
                    {"richtext": "<p>正文</p>"}
                ]
            },
        }

        with (
            patch.object(crawler, "_fetch_list", fetch_list),
            patch.object(
                crawler,
                "_resolve_node_id",
                AsyncMock(return_value=("node-id", False)),
            ),
            patch.object(
                crawler,
                "_fetch_detail",
                AsyncMock(return_value=detail_payload),
            ),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 3,
                    "pageSize": 10,
                    "maxItems": 50,
                    "requestInterval": 0,
                }
            )

        self.assertEqual(result["stats"]["fetched"], 10)
        self.assertEqual(result["stats"]["accepted"], 10)
        self.assertEqual(fetch_list.await_count, 1)
        self.assertEqual(
            fetch_list.await_args_list[0].kwargs["trading_process"],
            "3C14,3C51,3C52,3C61",
        )

    async def test_default_detail_concurrency_is_one(self):
        records = [self._record(i) for i in range(4)]
        active = 0
        max_active = 0

        async def fetch_detail(*args, **kwargs):
            nonlocal active, max_active
            active += 1
            max_active = max(max_active, active)
            await asyncio.sleep(0.01)
            active -= 1
            return {
                "errcode": 0,
                "data": {
                    "tradingNoticeColumnModelList": [
                        {"richtext": "<p>正文</p>"}
                    ]
                },
            }

        with (
            patch.object(
                crawler,
                "_fetch_list",
                AsyncMock(return_value=records),
            ),
            patch.object(crawler, "_fetch_detail", fetch_detail),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 2,
                    "pageSize": 10,
                    "requestInterval": 0,
                }
            )

        self.assertEqual(result["stats"]["accepted"], 4)
        self.assertEqual(max_active, 1)

    async def test_global_biz_node_mapping_skips_single_node_lookup(self):
        record = self._record(1)
        record["siteCode"] = "445200"
        record["tradingProcess"] = "3C51"
        used_node_ids = []

        async def fetch_detail(*args, **kwargs):
            used_node_ids.append(kwargs["node_id"])
            return {
                "errcode": 0,
                "data": {
                    "tradingNoticeColumnModelList": [
                        {"richtext": "<p>正文</p>"}
                    ]
                },
            }

        resolve_node = AsyncMock(return_value=("dynamic-node", False))
        with (
            patch.object(
                crawler,
                "_fetch_list",
                AsyncMock(return_value=[record]),
            ),
            patch.object(crawler, "_resolve_node_id", resolve_node),
            patch.object(crawler, "_fetch_detail", fetch_detail),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 1,
                    "pageSize": 10,
                    "requestInterval": 0,
                    "nodeIdMap": {"*|3C51": "global-node"},
                }
            )

        self.assertEqual(result["stats"]["accepted"], 1)
        self.assertEqual(used_node_ids, ["global-node"])
        resolve_node.assert_not_awaited()

    async def test_detail_requests_use_configured_bounded_concurrency(self):
        records = [self._record(i) for i in range(6)]
        active = 0
        max_active = 0

        async def fetch_detail(*args, **kwargs):
            nonlocal active, max_active
            active += 1
            max_active = max(max_active, active)
            await asyncio.sleep(0.02)
            active -= 1
            return {
                "errcode": 0,
                "data": {
                    "tradingNoticeColumnModelList": [
                        {"richtext": "<p>正文</p>"}
                    ]
                },
            }

        with (
            patch.object(
                crawler,
                "_fetch_list",
                AsyncMock(return_value=records),
            ),
            patch.object(
                crawler,
                "_resolve_node_id",
                AsyncMock(return_value=("node-id", False)),
            ),
            patch.object(crawler, "_fetch_detail", fetch_detail),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 1,
                    "pageSize": 10,
                    "maxConcurrency": 2,
                    "requestInterval": 0,
                }
            )

        self.assertEqual(result["stats"]["accepted"], 6)
        self.assertEqual(max_active, 2)

    async def test_total_budget_returns_partial_result_before_host_timeout(self):
        records = [self._record(i) for i in range(2)]

        async def slow_detail(*args, **kwargs):
            await asyncio.sleep(0.2)
            return {
                "errcode": 0,
                "data": {"tradingNoticeColumnModelList": []},
            }

        with (
            patch.object(
                crawler,
                "_fetch_list",
                AsyncMock(return_value=records),
            ),
            patch.object(
                crawler,
                "_resolve_node_id",
                AsyncMock(return_value=("node-id", False)),
            ),
            patch.object(crawler, "_fetch_detail", slow_detail),
        ):
            result = await crawler._collect(
                {
                    "maxPages": 1,
                    "pageSize": 10,
                    "maxConcurrency": 2,
                    "maxRunSeconds": 0.03,
                    "requestInterval": 0,
                }
            )

        self.assertTrue(result["stats"].get("timedOut"))
        self.assertEqual(result["stats"]["accepted"], 0)


if __name__ == "__main__":
    unittest.main()
