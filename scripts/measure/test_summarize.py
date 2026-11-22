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
            "firstDisplayMs": first, "settledMs": settled, "checked": checked, "found": found,
            "batches": [{"items": checked}]}


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

    def test_timeout_without_metrics_is_undetermined(self):
        self.assertEqual(s.unresolved_kind(lib(unknown=1, reasons={"TIMEOUT": 1}), None), "undetermined")

    def test_pool_busy_is_ours_even_with_evidence(self):
        self.assertEqual(s.unresolved_kind(lib(unknown=2, reasons={"TIMEOUT": 1, "POOL_BUSY": 1}), True), "ours")

    def test_our_http_error_is_ours(self):
        self.assertEqual(s.unresolved_kind(lib(stop="ERROR", error={"status": 429}), True), "ours")

    def test_daily_limit_stop_is_ours(self):
        self.assertEqual(s.unresolved_kind(lib(stop="DAILY_LIMIT", unknown=0), True), "ours")

    def test_time_stop_is_counted_apart(self):
        self.assertEqual(s.unresolved_kind(lib(stop="TIME", unknown=1, reasons={"TIMEOUT": 1}), True), "time")


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
    def diff(self, pre, post):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "pre.txt").write_text(pre, encoding="utf-8")
            (Path(d) / "post.txt").write_text(post, encoding="utf-8")
            return s.probe_diff(s.load_probe(Path(d) / "pre.txt"), s.load_probe(Path(d) / "post.txt"))

    def test_diff_counts_budget_slow_and_not_success(self):
        diff = self.diff(PRE, POST)
        self.assertEqual(s.budget_used(diff), 20)
        cs = s.client_stats(diff)
        self.assertEqual(cs["count"], 20)
        # 느린 호출은 성공 호출만 센다(성공 19 중 1.0초 버킷 밖 1). 성공 아님 1은 따로
        self.assertEqual(cs["slow"], 1)
        self.assertEqual(cs["notSuccess"], 1)

    def test_timeout_is_not_counted_twice(self):
        timeout = ('2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="UNKNOWN",uri="/bookExist",le="1.0"} 0\n'
                   '2026-11-25T05:05:00Z port=8081 http_client_requests_seconds_bucket{client_name="data4library.kr",outcome="UNKNOWN",uri="/bookExist",le="+Inf"} 1\n')
        cs = s.client_stats(self.diff(PRE, POST + timeout))
        self.assertEqual(cs["slow"] + cs["notSuccess"], 2)

    def test_budget_gauge_is_not_doubled_when_both_slots_are_up(self):
        two = lambda text: text + text.replace("port=8081", "port=8082")  # noqa: E731
        diff = self.diff(two(PRE), two(POST))
        self.assertEqual(s.budget_used(diff), 20)
        self.assertEqual(s.client_stats(diff)["count"], 40)


class EndToEnd(unittest.TestCase):
    """판정 규칙 시험. 계획과의 대조(표본 부족)는 Completeness에서 따로 본다"""

    def setUp(self):
        self._completeness = s.completeness
        s.completeness = lambda runs, plan: []

    def tearDown(self):
        s.completeness = self._completeness

    def report(self, recs, probes=True, extra=None):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "seoul-cityhall-r1.jsonl").write_text(
                "\n".join("MEASURE " + json.dumps(r) for r in recs), encoding="utf-8")
            for name, text in (extra or {}).items():
                (Path(d) / name).write_text(text, encoding="utf-8")
            if probes:
                (Path(d) / "probe-seoul-cityhall-r1-pre.txt").write_text(PRE, encoding="utf-8")
                (Path(d) / "probe-seoul-cityhall-r1-post.txt").write_text(POST, encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                s.main(d)
        return buf.getvalue()

    @staticmethod
    def rec(isbn, library, bin_="common"):
        return {"stage": "before", "pass": "first", "position": "seoul-cityhall", "round": 1, "isbn": isbn, "bin": bin_,
                "library": library, "search": {"status": 200}}

    def test_flags_budget_mismatch_and_judges_first_display(self):
        text = self.report([
            self.rec("1", lib()),
            self.rec("2", lib(stop="ROUND_LIMIT", first=6000, settled=6000, checked=20, found=0), "absent"),
        ])
        self.assertIn("| seoul-cityhall-r1 | 2 | 20 | 25 |", text)
        self.assertIn("⚠예산≠항목 수(-5)", text)
        self.assertIn("첫 표시: **미달**", text)

    def test_time_stop_is_left_out_of_settled_sample(self):
        text = self.report([
            self.rec("1", lib(settled=1500)),
            self.rec("2", lib(stop="TIME", settled=9900, unknown=1, reasons={"TIMEOUT": 1})),
        ])
        self.assertIn("확정: **통과** — 1건 중", text)
        self.assertIn("`TIME` 종료(따로 적음): 1건", text)

    def test_limit_429_lookups_are_left_out_and_hold_the_verdict(self):
        text = self.report([
            self.rec("1", lib()),
            self.rec("2", lib(stop="ERROR", error={"status": 429, "code": "DAILY_REQUEST_LIMIT_EXCEEDED"})),
        ])
        self.assertIn("측정 중단", text)
        self.assertIn("판정 보류(측정 중단", text)
        self.assertIn("우리 원인 0건", text)

    def test_unresolved_lookups_stay_in_settled_sample(self):
        text = self.report([
            self.rec("1", lib(settled=1500)),
            self.rec("2", lib(settled=6000, unknown=1, reasons={"POOL_BUSY": 1})),
        ])
        self.assertIn("확정: **미달** — 2건 중 50.0%", text)
        self.assertIn("참고(확정된 조회만): 1건 중 100.0%", text)

    def test_search_only_429_is_left_out(self):
        rec = self.rec("2", lib(settled=9000))
        rec["search"] = {"status": 429, "code": None}
        text = self.report([self.rec("1", lib()), rec])
        self.assertIn("제한에 걸린 조회 1건", text)
        self.assertIn("확정: **판정 보류(통과, 측정 중단)", text)

    def test_stop_after_full_sample_holds_time_verdicts(self):
        aborted = {"stage": "before", "pass": "first", "position": "seoul-cityhall", "round": 1, "aborted": "HTTP_429", "remaining": []}
        text = self.report([self.rec("1", lib())], extra={"seoul-cityhall-r2.jsonl": "MEASURE " + json.dumps(aborted)})
        self.assertIn("첫 표시: **판정 보류(통과, 측정 중단)", text)
        self.assertIn("확정: **판정 보류(통과, 측정 중단)", text)
        self.assertIn("판정 보류(측정 중단 — 위 경고)", text)

    def test_missing_probe_holds_judgement_instead_of_blaming_us(self):
        text = self.report([self.rec("1", lib(unknown=1, reasons={"TIMEOUT": 1}))], probes=False)
        self.assertIn("판정 보류", text)


    def test_missing_slow_bucket_holds_instead_of_counting_zero(self):
        no_buckets = "\n".join(l for l in POST.splitlines() if "_bucket" not in l) + "\n"
        pre = "\n".join(l for l in PRE.splitlines() if "_bucket" not in l) + "\n"
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "seoul-cityhall-r1.jsonl").write_text(
                "MEASURE " + json.dumps(self.rec("1", lib(unknown=2, reasons={"TIMEOUT": 2}))), encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-pre.txt").write_text(pre, encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-post.txt").write_text(no_buckets, encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                s.main(d)
        self.assertIn("⚠느린 호출 버킷 없음", buf.getvalue())
        self.assertIn("판정 보류(지표 없는 회차", buf.getvalue())

    def test_aborted_more_record_does_not_break_report(self):
        more = {"stage": "before", "pass": "more", "position": "seoul-cityhall", "round": 1, "isbn": "9", "bin": "absent",
                "rounds": [{"stop": "EXHAUSTED", "settledMs": 900, "checked": 5, "batches": [{"items": 5}]}],
                "totalChecked": 5, "totalFound": 0, "search": {"status": 200}}
        aborted = {"stage": "before", "pass": "more", "position": "seoul-cityhall", "round": 1, "aborted": "IP_DAILY_LIMIT", "remaining": []}
        text = self.report([self.rec("1", lib())], extra={"more.jsonl": "MEASURE " + json.dumps(more) + "\nMEASURE " + json.dumps(aborted)})
        self.assertIn("## 더 찾기 끝까지", text)
        self.assertIn("측정 중단", text)


class OutsideShare(unittest.TestCase):
    """규칙 2 다시 판정용 밖 몫: 측정 PC 배치 평균 − 서버 배치 평균"""

    PRE_B = PRE + '2026-11-25T05:00:00Z port=8081 library_batch_seconds_count{mode="NEARBY",stop="NONE"} 10\n' \
        + '2026-11-25T05:00:00Z port=8081 library_batch_seconds_sum{mode="NEARBY",stop="NONE"} 3.0\n'
    POST_B = POST + '2026-11-25T05:05:00Z port=8081 library_batch_seconds_count{mode="NEARBY",stop="NONE"} 12\n' \
        + '2026-11-25T05:05:00Z port=8081 library_batch_seconds_sum{mode="NEARBY",stop="NONE"} 3.6\n' \
        + '2026-11-25T05:05:00Z port=8081 library_batch_seconds_count{mode="SAVED",stop="NONE"} 99\n'

    def report(self, batches):
        rec = EndToEnd.rec("1", dict(lib(), batches=batches))
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "seoul-cityhall-r1.jsonl").write_text("MEASURE " + json.dumps(rec), encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-pre.txt").write_text(self.PRE_B, encoding="utf-8")
            (Path(d) / "probe-seoul-cityhall-r1-post.txt").write_text(self.POST_B, encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                s.main(d)
        return buf.getvalue()

    def test_m_and_threshold(self):
        # 측정 PC 평균 400ms, 서버 평균 (3.6−3.0)/(12−10) = 300ms → m 100ms, T 1.90초. SAVED 모드는 세지 않는다
        text = self.report([{"items": 5, "ms": 300, "status": 200}, {"items": 5, "ms": 500, "status": 200}])
        self.assertIn("밖 몫 m = 100ms → 규칙 2 새 문턱 T = 2.0 − m = **1.90초**", text)
        self.assertNotIn("⚠ seoul-cityhall-r1: 서버 배치", text)

    def test_count_mismatch_is_warned(self):
        text = self.report([{"items": 5, "ms": 300, "status": 200}])
        self.assertIn("서버 배치 2건 ≠ 측정 PC 배치 1건", text)
        self.assertIn("m을 그대로 믿지 말고", text)


class Completeness(unittest.TestCase):
    PLAN = {"positions": {"p": {"books": "b", "rounds": 2}}, "books": {"b": [{"isbn": "1", "bin": "common"}, {"isbn": "2", "bin": "absent"}]}}

    def runs(self, **overrides):
        first = lambda: [{"pass": "first"}, {"pass": "first"}]  # noqa: E731
        runs = {"p-r1": first(), "p-r2": first(), "p-second": [{"pass": "second"}] * 2,
                "p-browser": [{"pass": "browser", "position": "p", "isbn": "1", "bin": "common", "timedOut": False, "nonNetworkShareMs": 10},
                              {"pass": "browser", "position": "p", "isbn": "2", "bin": "absent", "timedOut": False, "nonNetworkShareMs": 12}],
                "more": [{"pass": "more"}], "reference": [{"pass": "reference"}]}
        runs.update(overrides)
        return {k: v for k, v in runs.items() if v is not None}

    def test_complete(self):
        self.assertEqual(s.completeness(self.runs(), self.PLAN), [])

    def test_short_round_and_missing_run(self):
        out = s.completeness(self.runs(**{"p-r2": [{"pass": "first"}], "reference": None}), self.PLAN)
        self.assertIn("p-r2: 기대 2건 중 1건", out)
        self.assertIn("reference: 실행 없음", out)

    def test_invalid_browser_sample(self):
        bad = [{"pass": "browser", "position": "p", "isbn": "1", "bin": "common", "timedOut": True, "nonNetworkShareMs": None},
               {"pass": "browser", "position": "p", "isbn": "2", "bin": "absent", "timedOut": False, "nonNetworkShareMs": 12}]
        out = s.completeness(self.runs(**{"p-browser": bad}), self.PLAN)
        self.assertIn("browser p-browser: common 묶음 1 몫을 못 잼", out)

    def test_right_count_but_wrong_bins_is_missing(self):
        same_bin = [{"pass": "browser", "position": "p", "isbn": "1", "bin": "common", "timedOut": False, "nonNetworkShareMs": 10}] * 2
        out = s.completeness(self.runs(**{"p-browser": same_bin}), self.PLAN)
        self.assertIn("browser p-browser: absent 묶음 2 기록 없음", out)

    def test_record_from_another_position_with_same_books_is_missing(self):
        plan = {"positions": {"p": {"books": "b", "rounds": 2}, "q": {"books": "b", "rounds": 2}}, "books": self.PLAN["books"]}
        other = [{"pass": "browser", "position": "q", "isbn": "1", "bin": "common", "timedOut": False, "nonNetworkShareMs": 10},
                 {"pass": "browser", "position": "q", "isbn": "2", "bin": "absent", "timedOut": False, "nonNetworkShareMs": 12}]
        runs = self.runs(**{"p-browser": other})
        runs.update({"q-r1": runs["p-r1"], "q-r2": runs["p-r2"], "q-second": runs["p-second"], "q-browser": other})
        out = s.completeness(runs, plan)
        self.assertIn("browser p-browser: common 묶음 1 기록 없음", out)
        self.assertNotIn("browser q-browser: common 묶음 1 기록 없음", out)

    def test_real_plan_expects_four_positions(self):
        exp = s.expected_runs(s.load_plan())
        self.assertEqual(exp["seoul-cityhall-r3"], ("first", 18))
        self.assertNotIn("wonju-cityhall-second", exp)
        self.assertEqual(exp["gangseo-office-browser"], ("browser", 4))

    def test_partial_folder_holds_first_display_verdict(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "seoul-cityhall-r1.jsonl").write_text(
                "MEASURE " + json.dumps(EndToEnd.rec("1", lib())), encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                s.main(d)
        self.assertIn("표본 부족 — 판정 보류", buf.getvalue())
        self.assertIn("첫 표시: **판정 보류(통과, 표본 부족, 브라우저 포함)", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
