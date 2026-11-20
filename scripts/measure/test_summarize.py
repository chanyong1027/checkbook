"""summarize.py 판정 함수 시험(합성 데이터). python3 -m unittest scripts/measure/test_summarize.py"""
import json
import math
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import summarize as s  # noqa: E402


def lib(stop="GOAL", unknown=0, reasons=None, error=None, first=1500, settled=1500, checked=5, found=5):
    return {"stop": stop, "unknown": unknown, "unknownReasons": reasons or {}, "error": error,
            "firstDisplayMs": first, "settledMs": settled, "checked": checked, "found": found}


class JudgeTimes(unittest.TestCase):
    def test_passes_when_95_percent_within_and_max_under_double(self):
        values = [1000] * 19 + [3900]
        self.assertEqual(s.judge_times(values, 2000)[0], "통과")

    def test_fails_when_max_over_double(self):
        values = [1000] * 99 + [4001]
        self.assertEqual(s.judge_times(values, 2000)[0], "미달")

    def test_fails_when_share_under_95(self):
        values = [1000] * 18 + [2500, 2500]
        self.assertEqual(s.judge_times(values, 2000)[0], "미달")

    def test_none_values_are_left_out(self):
        self.assertEqual(s.judge_times([None, 1000], 2000)[0], "통과")


class Unresolved(unittest.TestCase):
    def test_settled_lookup(self):
        self.assertIsNone(s.unresolved_kind(lib(), ext_evidence=True))

    def test_timeout_with_server_evidence_is_external(self):
        self.assertEqual(s.unresolved_kind(lib(unknown=1, reasons={"TIMEOUT": 1}), True), "external")

    def test_timeout_without_server_evidence_is_ours(self):
        self.assertEqual(s.unresolved_kind(lib(unknown=1, reasons={"TIMEOUT": 1}), False), "ours")

    def test_pool_busy_is_ours_even_with_evidence(self):
        self.assertEqual(s.unresolved_kind(lib(unknown=2, reasons={"TIMEOUT": 1, "POOL_BUSY": 1}), True), "ours")

    def test_our_http_error_is_ours(self):
        self.assertEqual(s.unresolved_kind(lib(stop="ERROR", error={"status": 429}), True), "ours")

    def test_daily_limit_stop_is_ours(self):
        self.assertEqual(s.unresolved_kind(lib(stop="DAILY_LIMIT", unknown=0), True), "ours")


class Quantile(unittest.TestCase):
    def test_interpolates_inside_bucket(self):
        buckets = {0.5: 50.0, 1.0: 100.0, math.inf: 100.0}
        self.assertAlmostEqual(s.quantile(buckets, 0.5), 0.5)
        self.assertAlmostEqual(s.quantile(buckets, 0.75), 0.75)

    def test_empty(self):
        self.assertIsNone(s.quantile({}, 0.5))


PRE = """2026-11-25T05:00:00Z port=8081 external_budget_used{provider="datanaru"} 100
2026-11-25T05:00:00Z port=8081 http_client_requests_seconds_count{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist"} 10
2026-11-25T05:00:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist",le="1.0"} 10
2026-11-25T05:00:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist",le="+Inf"} 10
"""
POST = """2026-11-25T05:05:00Z port=8081 external_budget_used{provider="datanaru"} 120
2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_count{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist"} 29
2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_count{client_name="data4library.kr",outcome="UNKNOWN",uri="/bookExist"} 1
2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist",le="1.0"} 28
2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="SUCCESS",uri="/bookExist",le="+Inf"} 30
"""


class Probe(unittest.TestCase):
    def test_diff_counts_budget_slow_and_not_success(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "pre.txt").write_text(PRE, encoding="utf-8")
            (Path(d) / "post.txt").write_text(POST, encoding="utf-8")
            diff = s.probe_diff(s.load_probe(Path(d) / "pre.txt"), s.load_probe(Path(d) / "post.txt"))
        self.assertEqual(s.budget_used(diff), 20)
        cs = s.client_stats(diff)
        self.assertEqual(cs["count"], 20)
        self.assertEqual(cs["slow"], 2)
        self.assertEqual(cs["notSuccess"], 1)


class EndToEnd(unittest.TestCase):
    def test_report_runs_on_synthetic_folder(self):
        recs = [
            {"stage": "before", "pass": "first", "position": "seoul-cityhall", "round": 1, "isbn": "1", "bin": "common",
             "library": lib(), "search": {"status": 200}},
            {"stage": "before", "pass": "first", "position": "seoul-cityhall", "round": 1, "isbn": "2", "bin": "absent",
             "library": lib(stop="ROUND_LIMIT", first=6000, settled=6000, checked=20, found=0), "search": {"status": 200}},
        ]
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "seoul-cityhall-r1.jsonl").write_text(
                "\n".join("MEASURE " + json.dumps(r) for r in recs), encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-pre.txt").write_text(PRE, encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-post.txt").write_text(POST, encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                s.main(d)
        text = buf.getvalue()
        self.assertIn("| seoul-cityhall-r1 | 2 | 20 | 25 |", text)
        self.assertIn("첫 표시: **미달**", text)


if __name__ == "__main__":
    unittest.main()
