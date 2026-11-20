"""api_check.py 판정 시험(합성 기록). python3 -m unittest scripts/measure/test_api_check.py"""
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import api_check as a  # noqa: E402


def region(code=31, complete=True, num=10):
    return {"region": code, "pages": [{"pageNo": 1, "sec": 0.5, "error": None, "numFound": num, "resultNum": num, "got": num}],
            "numFound": num, "distinct": num if complete else num - 1, "duplicates": 0, "complete": complete}


def b(calls=1, sec=0.8, regions=None):
    regions = regions or [region()]
    return {"phase": "B", "round": 1, "position": "p", "isbn": "1", "regions": [r["region"] for r in regions],
            "stageSec": sec, "calls": calls, "perRegion": regions}


def c(checks, complete=True):
    return {"phase": "C", "list": "seoul", "position": "p", "isbn": "1", "bin": "common", "listComplete": complete,
            "listed": 10, "perRegion": [region(complete=complete)], "checks": checks}


def check(has="N", notice=False, error=None):
    return {"libCode": "X", "name": "어느도서관", "km": 1.0, "sec": 0.4, "error": error, "hasBook": has,
            "falseAbsent": has == "Y", "notice": notice, "noticeCity": notice}


def report(recs):
    with tempfile.TemporaryDirectory() as d:
        f = Path(d) / "r.jsonl"
        f.write_text("\n".join(json.dumps(r) for r in [{"phase": "start", "at": "t", "limit": 0}] + recs), encoding="utf-8")
        buf = StringIO()
        with redirect_stdout(buf):
            a.summarize(str(f))
    return buf.getvalue()


class Rules(unittest.TestCase):
    def test_rule1_stops_over_four_calls(self):
        self.assertIn("최대 5 → **멈춤", report([b(calls=5)]))

    def test_rule2_uses_95_percent_and_double_max(self):
        ok = [b(sec=1.0)] * 19 + [b(sec=2.9)]
        self.assertIn("최대 2.90초 → **그대로**", report(ok))
        bad = [b(sec=1.0)] * 19 + [b(sec=3.1)]
        self.assertIn("→ **멈춤**", report(bad).split("규칙 2")[1])

    def test_rule3_notice_mismatch_does_not_stop(self):
        text = report([c([check("Y", notice=True), check()])])
        self.assertIn("공지 기관 아닌 곳 **0건**, 공지 기관(공급자 재수집, 따로 셈) 1건", text)
        self.assertIn("판정 → **그대로**", text)

    def test_rule3_other_mismatch_stops(self):
        self.assertIn("판정 → **멈춤", report([c([check("Y")])]))

    def test_rule3_errors_hold(self):
        self.assertIn("판정 → **판정 보류", report([c([check(error="URLError", has=None)])]))

    def test_incomplete_region_answer_holds_rules_1_and_2(self):
        text = report([b(regions=[region(complete=False)])])
        self.assertIn("규칙 1(호출 ≤ 4): 최대 1 → **판정 보류(불완전한 시도 응답 1건)", text)

    def test_coordinate_list_error_holds_rule3(self):
        text = report([{"phase": "C", "error": "libSrch 32: URLError"}, c([check()])])
        self.assertIn("좌표 목록 오류·일부만 받음 1건", text)
        self.assertIn("판정 → **판정 보류", text)

    def test_upper_bound_leaves_out_notice_checks(self):
        text = report([c([check(), check(notice=True)])])
        self.assertIn("공지 기관을 뺀 확인 1곳", text)

    def test_stop_is_not_masked_by_incomplete_answers(self):
        text = report([b(calls=5), b(regions=[region(complete=False)])])
        self.assertIn("최대 5 → **멈춤", text)

    def test_partial_coordinate_list_and_empty_check_list_hold(self):
        self.assertIn("판정 → **판정 보류", report([{"phase": "C", "warn": "libSrch 31: 일부만"}, c([check()])]))
        self.assertIn("확인 0곳인 목록 ['seoul']", report([c([])]))
        self.assertIn("판정 → **판정 보류", report([c([])]))

    def test_incomplete_list_is_reported(self):
        self.assertIn("불완전(오류·쪽마다 numFound 다름·코드 수 ≠ numFound) 1건", report([c([], complete=False)]))


if __name__ == "__main__":
    unittest.main()
