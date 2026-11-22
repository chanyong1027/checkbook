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


def report(recs, expected=None, finished=True, limit=0):
    """기본은 기록한 만큼이 기대 수이고 끝 표시가 있는 완전한 실행"""
    if expected is None:
        expected = {"A": 0, "B": sum(1 for r in recs if r.get("phase") == "B"),
                    "C": sum(1 for r in recs if r.get("phase") == "C" and "checks" in r)}
    lines = [{"phase": "start", "at": "t", "limit": limit, "expected": expected}] + recs + ([{"phase": "end"}] if finished else [])
    with tempfile.TemporaryDirectory() as d:
        f = Path(d) / "r.jsonl"
        f.write_text("\n".join(json.dumps(r) for r in lines), encoding="utf-8")
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
        self.assertIn("규칙 1·2: 완전한 표본이 없음 → **판정 보류(불완전한 시도 응답 1건)", text)

    def test_one_slow_sample_in_partial_run_is_not_yet_a_stop(self):
        # 60건 중 1건만 모였고 2초 — 나머지 59건이 좋으면 59/60으로 통과할 수 있다
        text = report([b(sec=2.0)], expected={"A": 0, "B": 60, "C": 0})
        self.assertIn("넘은 것 1건, 최대 2.00초 → **판정 보류(표본 부족: B 1/60)", text)

    def test_timed_out_sample_does_not_stop_rule2(self):
        recs = [b(sec=1.0)] * 19 + [b(sec=15.0, regions=[region(complete=False)])]
        text = report(recs)
        self.assertIn("최대 1.00초 → **판정 보류(불완전한 시도 응답 1건)", text)

    def test_certain_failure_stops_even_in_partial_run(self):
        text = report([b(sec=2.0)] * 4, expected={"A": 0, "B": 60, "C": 0})
        self.assertIn("넘은 것 4건, 최대 2.00초 → **멈춤**", text)

    def test_old_file_without_expected_count_does_not_stop_on_ratio(self):
        # 기대 수가 없으면 비율 실패는 확정할 수 없다 — 2초 1건은 보류, 3초 넘는 값은 멈춤
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "old.jsonl"
            f.write_text("\n".join(json.dumps(r) for r in [{"phase": "start", "at": "t", "limit": 0}, b(sec=2.0), {"phase": "end"}]),
                         encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                a.summarize(str(f))
            self.assertIn("최대 2.00초 → **판정 보류", buf.getvalue())
            f.write_text("\n".join(json.dumps(r) for r in [{"phase": "start", "at": "t", "limit": 0}, b(sec=3.5), {"phase": "end"}]),
                         encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                a.summarize(str(f))
            self.assertIn("최대 3.50초 → **멈춤**", buf.getvalue())

    def test_rule2_target_override(self):
        recs = [b(sec=1.7)] * 20
        self.assertIn("최대 1.70초 → **멈춤**", report(recs))
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "r.jsonl"
            f.write_text("\n".join(json.dumps(r) for r in [{"phase": "start", "at": "t", "limit": 0, "expected": {"A": 0, "B": 20, "C": 0}}]
                                   + recs + [{"phase": "end"}]), encoding="utf-8")
            buf = StringIO()
            with redirect_stdout(buf):
                a.summarize(str(f), 1.8)
        self.assertIn("단계 ≤ 1.80초, 다시 정한 문턱", buf.getvalue())
        self.assertIn("최대 1.70초 → **그대로**", buf.getvalue())

    def test_rule2_target_rejects_nan_and_out_of_range(self):
        import subprocess
        script = str(Path(__file__).with_name("api_check.py"))
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "r.jsonl"
            f.write_text(json.dumps({"phase": "start", "at": "t", "limit": 0}), encoding="utf-8")
            for bad in ("nan", "0", "2.5"):
                res = subprocess.run([sys.executable, script, "--summarize", str(f), "--rule2-target", bad],
                                     capture_output=True, text=True)
                self.assertNotEqual(res.returncode, 0, bad)
                self.assertIn("0보다 크고 2초보다 작아야", res.stderr, bad)
            res = subprocess.run([sys.executable, script, "--summarize", str(f), "--rule2-target", "abc"], capture_output=True, text=True)
            self.assertIn("숫자여야 한다", res.stderr)
            self.assertNotIn("Traceback", res.stderr)
            ok = subprocess.run([sys.executable, script, "--summarize", str(f), "--rule2-target", "1.8"], capture_output=True, text=True)
            self.assertEqual(ok.returncode, 0, ok.stderr)

    def test_aborted_run_holds(self):
        text = report([c([check()]), {"phase": "aborted", "reason": "HTTP 429"}], finished=False)
        self.assertIn("판정 보류(표본 부족: 끝 표시 없음)", text)

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

    def test_partial_run_holds_pass_but_keeps_stop(self):
        text = report([c([check()])], expected={"A": 0, "B": 0, "C": 54})
        self.assertIn("C 1/54", text)
        self.assertIn("판정 → **판정 보류(표본 부족: C 1/54)", text)
        self.assertIn("판정 → **멈춤", report([c([check("Y")])], expected={"A": 0, "B": 0, "C": 54}))

    def test_unfinished_or_limit_run_holds(self):
        self.assertIn("판정 보류(표본 부족: 끝 표시 없음)", report([c([check()])], finished=False))
        self.assertIn("판정 보류(표본 부족: --limit 실행)", report([c([check()])], limit=1))

    def test_dropped_coordinates_are_noted_not_held(self):
        text = report([{"phase": "C", "note": "libSrch 32: 좌표 없는 1곳 제외(앱도 적재하지 않음)"}, c([check()])])
        self.assertIn("참고: libSrch 32: 좌표 없는 1곳 제외", text)
        self.assertIn("판정 → **그대로**", text)

    def test_note_is_printed_under_rule3(self):
        text = report([b(), {"phase": "C", "note": "libSrch 32: 좌표 없는 1곳 제외(앱도 적재하지 않음)"}, c([check()])])
        self.assertLess(text.index("## 규칙 3"), text.index("참고: libSrch 32"))

    def test_partial_run_keeps_original_hold_reason(self):
        text = report([c([check(error="URLError", has=None)])], expected={"A": 0, "B": 0, "C": 54})
        self.assertIn("판정 → **판정 보류(확인 못 한 칸·불완전 목록·좌표 목록 오류), 표본 부족: C 1/54", text)

    def test_incomplete_list_is_reported(self):
        self.assertIn("불완전(오류·쪽마다 numFound 다름·코드 수 ≠ numFound) 1건", report([c([], complete=False)]))


class FakeResponse:
    def __init__(self, payload):
        self._body = json.dumps(payload).encode("utf-8")
        self.status = 200

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class ClientErrors(unittest.TestCase):
    """정보나루 오류 칸: 키·한도는 바로 멈추고, 그 밖의 오류는 연달아 3번일 때만 멈춘다"""

    def call(self, client, error):
        original = a.urllib.request.urlopen
        a.urllib.request.urlopen = lambda req, timeout: FakeResponse({"response": {"error": error}})
        try:
            return client.get("bookExist", libCode="1", isbn13="1")
        finally:
            a.urllib.request.urlopen = original

    def test_key_or_limit_error_stops_at_once(self):
        client = a.Client("k")
        self.call(client, "인증키가 유효하지 않습니다")
        self.assertTrue(client.fatal.startswith("error 칸(키·한도)"))

    def test_other_error_stops_only_after_streak(self):
        client = a.Client("k")
        self.call(client, "잘못된 도서관 코드")
        self.call(client, "잘못된 도서관 코드")
        self.assertIsNone(client.fatal)
        self.call(client, "잘못된 도서관 코드")
        self.assertIn("3회 연속", client.fatal)

    def test_unregistered_ip_limit_stops_at_once(self):
        client = a.Client("k")
        original = a.urllib.request.urlopen
        a.urllib.request.urlopen = lambda req, timeout: FakeResponse(
            {"response": {"errCode": "outOflimit", "error": "1일 500건 이상 요청 시 IP 등록이 필요합니다."}})
        try:
            client.get("libSrchByBook", isbn="1", region=11)
        finally:
            a.urllib.request.urlopen = original
        self.assertIsNotNone(client.fatal)

    def test_success_resets_streak(self):
        client = a.Client("k")
        self.call(client, "잘못된 도서관 코드")
        self.call(client, "잘못된 도서관 코드")
        original = a.urllib.request.urlopen
        a.urllib.request.urlopen = lambda req, timeout: FakeResponse({"response": {"result": {"hasBook": "N"}}})
        try:
            client.get("bookExist", libCode="1", isbn13="1")
        finally:
            a.urllib.request.urlopen = original
        self.call(client, "잘못된 도서관 코드")
        self.assertIsNone(client.fatal)


class DbCoords(unittest.TestCase):
    def run_with(self, returncode, stdout="", stderr=""):
        original = a.subprocess.run
        a.subprocess.run = lambda *x, **k: type("R", (), {"returncode": returncode, "stdout": stdout, "stderr": stderr})()
        try:
            return a.db_library_coords()
        finally:
            a.subprocess.run = original

    def test_failure_is_reported_not_swallowed(self):
        coords, bad, err = self.run_with(1, stderr="no such service: postgres")
        self.assertIsNone(coords)
        self.assertIn("no such service", err)

    def test_bad_lines_are_counted(self):
        coords, bad, err = self.run_with(0, stdout="111|37.5|127.0\n222|x|y\n")
        self.assertEqual(coords, {"111": (37.5, 127.0)})
        self.assertEqual(bad, 1)
        self.assertIsNone(err)


class AbortPath(unittest.TestCase):
    def test_main_stops_and_writes_aborted_without_end(self):
        class FatalClient:
            def __init__(self, key):
                self.calls = 0
                self.fatal = None

            def get(self, path, **params):
                self.calls += 1
                self.fatal = "HTTP 429"
                return None, 0.1, "HTTP 429"

        plan = {"positions": {"seoul-cityhall": {"lat": 37.5, "lon": 127.0, "books": "seoul", "rounds": 1, "regions": [11]},
                              "ansan-cityhall": {"lat": 37.3, "lon": 126.8, "books": "gyeonggi", "rounds": 1, "regions": [31]},
                              "wonju-cityhall": {"lat": 37.3, "lon": 127.9, "books": "gangwon", "rounds": 1, "regions": [32]}},
                "bookRegions": {"seoul": 11, "gyeonggi": 31, "gangwon": 32},
                "noticeInstitutions": {"names": [], "cities": []},
                "books": {k: [{"isbn": "9780000000001", "bin": "common", "title": "t"}] for k in ("seoul", "gyeonggi", "gangwon")}}
        original_client, original_sleep, original_argv = a.Client, a.time.sleep, sys.argv
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "plan.json").write_text(json.dumps(plan), encoding="utf-8")
            (Path(d) / ".env").write_text("DATANARU_AUTH_KEY=k\n", encoding="utf-8")
            a.Client, a.time.sleep = FatalClient, (lambda s: None)
            sys.argv = ["api_check.py", "--plan", str(Path(d) / "plan.json"), "--env", str(Path(d) / ".env"), "--out", str(Path(d) / "o.jsonl")]
            try:
                with self.assertRaises(SystemExit) as cm:
                    a.main()
                self.assertEqual(cm.exception.code, 1)
                recs = [json.loads(line) for line in (Path(d) / "o.jsonl").read_text(encoding="utf-8").splitlines()]
            finally:
                a.Client, a.time.sleep, sys.argv = original_client, original_sleep, original_argv
        phases = [r["phase"] for r in recs]
        self.assertIn("aborted", phases)
        self.assertNotIn("end", phases)
        self.assertNotIn("B", phases)  # 첫 호출에서 멈춰 B·C를 부르지 않는다
        self.assertEqual(next(r for r in recs if r["phase"] == "aborted")["calls"], 1)


if __name__ == "__main__":
    unittest.main()
