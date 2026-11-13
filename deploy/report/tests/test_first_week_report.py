"""첫 주 지표 보고 시험. 실행: python3 -m unittest discover -s deploy/report/tests

서버의 실제 원천(Nginx 로그·journald·DB·액추에이터) 대신 합성 데이터를 넣는다. 공급자 응답 원문은 쓰지 않는다.
"""
import datetime as dt
import gzip
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import first_week_report as r  # noqa: E402

DAY = dt.date(2026, 11, 15)
START, END = r.day_window(DAY)  # KST 11-15 00:00 ~ 11-16 00:00 = UTC 11-14 15:00 ~ 11-15 15:00


def access(ip, utc, path, status=200):
    """Nginx combined 형식 한 줄. 서버 시간대는 UTC로 가정한다"""
    return (f'{ip} - - [{utc:%d/%b/%Y:%H:%M:%S} +0000] "GET {path} HTTP/1.1" {status} 12 "-" "Mozilla/5.0"')


def utc(h, m=0, day=15):
    return dt.datetime(2026, 11, day, h, m, tzinfo=dt.timezone.utc)


class DayWindowTest(unittest.TestCase):

    def test_KST_하루를_UTC로_바꾼다(self):
        self.assertEqual(START, dt.datetime(2026, 11, 14, 15, 0, tzinfo=dt.timezone.utc))
        self.assertEqual(END - START, dt.timedelta(days=1))


class AccessLogTest(unittest.TestCase):

    def test_상세_검색_IP별_분포와_시간대를_센다(self):
        lines = [
            access("203.0.113.1", utc(1), "/api/search?code=9788937460449"),
            access("203.0.113.1", utc(1, 5), "/api/search?code=9788937460450"),
            access("203.0.113.1", utc(1, 9), "/api/search?code=9788937460451"),
            access("203.0.113.2", utc(1, 30), "/api/search?code=9788937460449"),
            access("203.0.113.2", utc(2), "/api/books/search?q=abc"),
            access("203.0.113.3", utc(3), "/api/public-libraries/availability/batch"),
            access("198.51.100.9", utc(4), "/api/health"),  # 외부 헬스 체크는 사용량에서 뺀다
        ]
        s = r.summarize_access(lines, START, END)
        self.assertTrue(s.alive)
        self.assertEqual(s.detail, 4)
        self.assertEqual(s.search, 1)
        self.assertEqual(s.api_total, 6)
        self.assertEqual(s.unique_ips, 3)
        self.assertEqual(s.detail_ips, 2)
        self.assertEqual(s.detail_per_ip, (2.0, 1, 3, 3))  # 평균, 중앙(아래쪽), 90%, 최대
        # UTC 01시 = KST 10시
        self.assertEqual(s.hourly[10], 4)
        self.assertEqual(s.peak_hour, (10, 4))

    def test_거절_응답은_상세와_IP별_분포에서_뺀다(self):
        lines = [access("203.0.113.1", utc(1), "/api/search?code=1"),
                 *[access("203.0.113.66", utc(1, i), "/api/search?code=1", status=429) for i in range(30)],
                 access("203.0.113.67", utc(2), "/api/search?code=../../etc", status=400)]
        s = r.summarize_access(lines, START, END)
        self.assertEqual(s.detail, 1)
        self.assertEqual(s.detail_per_ip, (1.0, 1, 1, 1))
        self.assertEqual((s.api_total, s.not_ok, s.status_429), (32, 31, 30))

    def test_창_밖의_줄은_세지_않는다(self):
        lines = [
            access("203.0.113.1", utc(14, 59, day=14), "/api/search?code=1"),  # KST 11-14 23:59
            access("203.0.113.1", utc(15, 0, day=15), "/api/search?code=1"),   # KST 11-16 00:00
            access("198.51.100.9", utc(5), "/api/health"),
        ]
        s = r.summarize_access(lines, START, END)
        self.assertEqual(s.detail, 0)
        self.assertTrue(s.alive)

    def test_헬스_체크만_있으면_사용량_0이_진짜_0이다(self):
        s = r.summarize_access([access("198.51.100.9", utc(5), "/api/health")], START, END)
        self.assertTrue(s.alive)
        self.assertEqual(s.detail, 0)
        self.assertIsNone(s.detail_per_ip)

    def test_창_안에_줄이_하나도_없으면_수집_안_됨(self):
        s = r.summarize_access([access("198.51.100.9", utc(5, day=13), "/api/health")], START, END)
        self.assertFalse(s.alive)

    def test_깨진_줄은_건너뛴다(self):
        lines = ['203.0.113.1 - - [15/Nov/2026:01:00:00 +0000] "\\x16\\x03\\x01" 400 0 "-" "-"', "garbage"]
        s = r.summarize_access(lines, START, END)
        self.assertEqual(s.api_total, 0)


class NginxLimitTest(unittest.TestCase):

    def line(self, local, zone="checkbook_api", client="203.0.113.7", dry=""):
        return (f"{local:%Y/%m/%d %H:%M:%S} [error] 812#812: *9301 limiting requests{dry}, excess: 20.840 by zone "
                f'"{zone}", client: {client}, server: _, request: "GET /api/search?code=1 HTTP/1.1", host: "x"')

    def test_영역별_거절과_고유_IP를_센다(self):
        lines = [self.line(dt.datetime(2026, 11, 15, 1)), self.line(dt.datetime(2026, 11, 15, 1, 1)),
                 self.line(dt.datetime(2026, 11, 15, 2), zone="checkbook_search", client="203.0.113.8"),
                 "2026/11/15 01:00:00 [error] 1#1: *1 connect() failed (111: Connection refused)"]
        s = r.summarize_nginx_limits(lines, START, END, dt.timezone.utc)
        self.assertEqual(s.rejected, 3)
        self.assertEqual(s.by_zone, {"checkbook_api": 2, "checkbook_search": 1})
        self.assertEqual(s.unique_clients, 2)

    def test_dry_run_줄은_따로_센다(self):
        s = r.summarize_nginx_limits([self.line(dt.datetime(2026, 11, 15, 1), dry=", dry run")],
                                     START, END, dt.timezone.utc)
        self.assertEqual(s.rejected, 0)
        self.assertEqual(s.dry_run, 1)

    def test_서버_시간대를_반영한다(self):
        # 서버가 KST라면 로그의 11-16 00:30은 창 밖
        kst = dt.timezone(dt.timedelta(hours=9))
        s = r.summarize_nginx_limits([self.line(dt.datetime(2026, 11, 16, 0, 30))], START, END, kst)
        self.assertEqual(s.rejected, 0)


class AppLogTest(unittest.TestCase):

    def test_앱_로그에서_건수를_센다(self):
        p = "2026-11-15T01:00:00.000Z  {lv} 1 --- [checkbook] [exec-1] c.c.X : "
        lines = [
            p.format(lv="INFO") + "통합 검색: code=9788937460449, standard=true, source=YES24, yes24=true",
            p.format(lv="INFO") + "통합 검색: code=9788937460450, standard=true, source=YES24, yes24=true",
            p.format(lv="INFO") + "밀리 매칭 실패: title=가, hintTitle=null, author=나, publisher=다, candidateCount=3",
            p.format(lv="INFO") + "밀리 검색 결과 0건: keyword=가, title=가",
            p.format(lv="INFO") + "밀리 검색 결과 0건: keyword=라, title=라",
            # IP당 하루 첫 거절만 남고 끝자리는 가린다 → 203.0.113.1과 .2는 한 대역
            p.format(lv="WARN") + "사용자 하루 요청 한도(500) 초과: client=203.0.113.x, path=/api/search",
            p.format(lv="WARN") + "사용자 하루 요청 한도(500) 초과: client=203.0.113.x, path=/api/search",
            p.format(lv="WARN") + "사용자 하루 요청 한도(500) 초과: client=198.51.100.x, path=/api/search",
            p.format(lv="WARN") + "yes24 하루 예산 차단선 도달 — 자정(KST)까지 사용자 요청 호출을 막음 (key=YES24, 사용 19000)",
            p.format(lv="WARN") + "datanaru 오늘 호출 24000회 — 하루 한도 30000의 80% 도달 (차단선 28500)",
            p.format(lv="WARN") + "통합 검색 일부 실패: code=1, failed=[NEW_BOOK:TIMEOUT(read timed out), "
                                  "SUBSCRIPTION:ERROR(a, B:C(d))]",
            p.format(lv="WARN") + "통합 검색 일부 실패: code=2, failed=[NEW_BOOK:TIMEOUT(x)]",
            # 원인 글의 괄호가 짝이 안 맞아도 뒤 섹션을 놓치지 않는다
            p.format(lv="WARN") + "통합 검색 일부 실패: code=3, failed=[USED_BOOK:ERROR(unclosed ( paren), "
                                  "PUBLIC_LIBRARY:TIMEOUT(y)]",
            p.format(lv="INFO") + "스케줄 트리거: LOAN",
        ]
        s = r.summarize_app_log(lines)
        self.assertTrue(s.alive)
        self.assertEqual(s.searches, 2)
        self.assertEqual(s.millie_failed, 1)
        self.assertEqual(s.millie_zero, 2)
        self.assertEqual(s.client_rejected, 3)  # 거절된 IP 수
        self.assertEqual(s.client_rejected_ips, 2)  # 가린 대역 수
        self.assertEqual(s.budget_blocked, ["yes24"])
        self.assertEqual(s.budget_warned, ["datanaru"])
        self.assertEqual(s.section_failed, {"NEW_BOOK": 2, "SUBSCRIPTION": 1, "USED_BOOK": 1, "PUBLIC_LIBRARY": 1})

    def test_앱_로그가_0줄이면_수집_안_됨(self):
        # 매일 도는 스케줄(인기 도서 갱신)이 로그를 남기므로 하루 0줄은 수집 문제로 본다
        self.assertFalse(r.summarize_app_log([]).alive)


PROM = """# HELP process_start_time_seconds x
process_start_time_seconds 1.7631504E9
yes24_permit_wait_seconds_count 10.0
yes24_permit_wait_seconds_sum 0.5
yes24_permit_wait_seconds_max 0.12
yes24_rate_limited_total{by="self",kind="second"} 3.0
yes24_rate_limited_total{by="server",kind="day"} 0.0
search_section_total{reason="NONE",section="YES24",status="SUCCESS"} 90.0
search_section_total{reason="TIMEOUT",section="YES24",status="FAILED"} 2.0
search_section_total{reason="NOT_STANDARD",section="PUBLIC_LIBRARY",status="SKIPPED"} 5.0
library_batch_items_total{state="AVAILABLE"} 30.0
library_batch_items_total{state="NOT_HELD"} 70.0
http_client_requests_seconds_count{client_name="data4library.kr",error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/bookExist?authKey={key}&libCode={code}&isbn13={isbn}&format=json"} 25.0
http_client_requests_seconds_count{client_name="data4library.kr",error="none",exception="none",method="GET",outcome="SUCCESS",status="200",uri="/api/loanItemSrch?authKey={key}&pageNo=1&pageSize={size}&format=json"} 4.0
external_budget_limit{kind="block",provider="yes24"} 19000.0
external_budget_limit{kind="daily",provider="yes24"} 20000.0
external_budget_limit{kind="block",provider="datanaru"} 28500.0
external_budget_rejected_total{provider="yes24"} 1.0
external_budget_sync_failures_total 0.0
"""


class MetricsTest(unittest.TestCase):

    def test_프로메테우스_글을_읽는다(self):
        samples = r.parse_prometheus(PROM)
        self.assertIn(("yes24_rate_limited_total", {"by": "self", "kind": "second"}, 3.0), samples)

    def test_앱_시작_뒤_합계를_정리한다(self):
        m = r.summarize_metrics({"blue": r.parse_prometheus(PROM)})
        self.assertEqual(m.slots, ["blue"])
        self.assertEqual(m.started_at, dt.datetime.fromtimestamp(1.7631504e9, dt.timezone.utc))
        self.assertEqual(m.permit_wait, (10, 0.05, 0.12))  # 건수, 평균 초, 최대 초
        self.assertEqual(m.yes24_rate_limited, {"self/second": 3})
        self.assertEqual(m.section, {"YES24": {"SUCCESS": 90, "FAILED": 2},
                                     "PUBLIC_LIBRARY": {"SKIPPED": 5}})
        # 적중률 근사 = 1 - bookExist 호출 / 배치 기관 수 = 1 - 25/100
        self.assertAlmostEqual(m.hit_rate, 0.75)
        self.assertEqual(m.block_limits, {"yes24": 19000, "datanaru": 28500})
        self.assertEqual(m.budget_rejected, {"yes24": 1})
        self.assertEqual(m.sync_failures, 0)

    def test_bookExist는_base_url_경로가_붙어도_센다(self):
        m = r.summarize_metrics({"blue": r.parse_prometheus(PROM)})
        self.assertEqual(m.book_exist_calls, 25)  # loanItemSrch 4건은 빼고

    def test_NaN_게이지에_멈추지_않는다(self):
        m = r.summarize_metrics({"blue": r.parse_prometheus(
            'process_start_time_seconds 1.7E9\nexternal_budget_limit{kind="block",provider="yes24"} NaN\n'
            'ratelimit_client_rejected_total 7.0\n')})
        self.assertEqual(m.block_limits, {"yes24": 0})
        self.assertEqual(m.client_rejected_total, 7)

    def test_배치가_없으면_적중률은_없다(self):
        text = "process_start_time_seconds 1.7E9\nlibrary_batch_items_total{state=\"AVAILABLE\"} 0\n"
        self.assertIsNone(r.summarize_metrics({"blue": r.parse_prometheus(text)}).hit_rate)

    def test_지표가_하나도_없는_응답은_수집_안_됨(self):
        m = r.summarize_metrics({"blue": r.parse_prometheus("<html>502 Bad Gateway</html>")})
        self.assertFalse(m.alive)
        self.assertIn("지표가 없음", m.note)

    def test_한_칸만_지표가_빠져도_합계는_모름이다(self):
        # CodeRabbit #87: blue 정상 + green 오류 페이지면 blue 값만으로 실패 0을 믿으면 안 된다
        m = r.summarize_metrics({"blue": r.parse_prometheus(PROM + 'external_budget_used{provider="yes24"} 0\n'),
                                 "green": r.parse_prometheus("<html>error</html>")})
        self.assertTrue(m.alive)
        self.assertIsNone(m.sync_failures)
        self.assertIsNone(m.today_used)
        self.assertIn("칸 green 응답에 지표 없음", m.note)

    def test_한_칸만_지표가_빠지면_예산은_미확정(self):
        prom = {"blue": r.parse_prometheus(PROM), "green": r.parse_prometheus("<html>error</html>")}

        class TwoSlots(FakeSources):
            def prometheus(self):
                return prom
        rep = r.build_report(DAY, TwoSlots())
        self.assertIn("응답에 동기화 실패 지표가 없음", rep.budget.note)

    def test_동기화_지표가_없으면_0이_아니라_모름이다(self):
        m = r.summarize_metrics({"blue": r.parse_prometheus("process_start_time_seconds 1.7E9\n")})
        self.assertIsNone(m.sync_failures)
        self.assertIsNone(m.today_used)

    def test_칸이_하나도_없으면_수집_안_됨(self):
        self.assertFalse(r.summarize_metrics({}).alive)


class BudgetTest(unittest.TestCase):

    def test_공급자와_전자도서관을_나눈다(self):
        b = r.summarize_budget([("YES24", 120), ("DATANARU", 3000), ("elibrary:12", 3), ("elibrary:40", 1)],
                               last_update_epoch=END.timestamp() + 60, window_end=END)
        self.assertTrue(b.alive)
        self.assertEqual(b.providers, {"yes24": 120, "datanaru": 3000})
        self.assertEqual((b.elibrary_sites, b.elibrary_calls), (2, 4))

    def test_행이_있어도_그_뒤_기록이_없으면_미확정이다(self):
        b = r.summarize_budget([("YES24", 120)], last_update_epoch=END.timestamp() - 3600, window_end=END)
        self.assertTrue(b.alive)
        self.assertTrue(b.note.startswith("미확정"))

    def test_어제_행이_없어도_동기화가_뒤에_돌았으면_0이다(self):
        b = r.summarize_budget([], last_update_epoch=END.timestamp() + 60, window_end=END)
        self.assertTrue(b.alive)
        self.assertEqual(b.providers, {})

    def test_어제_행이_없고_동기화도_멈췄으면_수집_안_됨(self):
        b = r.summarize_budget([], last_update_epoch=START.timestamp() - 60, window_end=END)
        self.assertFalse(b.alive)

    def test_갱신_기록이_아예_없으면_수집_안_됨(self):
        b = r.summarize_budget([], last_update_epoch=None, window_end=END)
        self.assertFalse(b.alive)
        self.assertIn("기록 없음", b.note)


class ReadLogsTest(unittest.TestCase):

    def test_잘린_gz_하나가_나머지_파일을_버리게_하지_않는다(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "access.log"), "w") as f:
                f.write("오늘 줄\n")
            with gzip.open(os.path.join(d, "access.log.2.gz"), "wt") as f:
                f.write("x" * 1000)
            with open(os.path.join(d, "access.log.2.gz"), "r+b") as f:
                f.truncate(30)
            an_hour_ago = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
            lines, failed = r._read_logs(os.path.join(d, "access.log*"), an_hour_ago)
            self.assertEqual(lines, ["오늘 줄"])
            self.assertEqual(failed, ["access.log.2.gz(EOFError)"])  # 창 시작 뒤에 수정된 파일이라 창의 줄이 있었을 수 있다

    def test_창_전에_끝난_파일은_못_읽어도_일부로_치지_않는다(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "access.log"), "w") as f:
                f.write("오늘 줄\n")
            old = os.path.join(d, "access.log.9.gz")
            with open(old, "wb") as f:
                f.write(b"not gzip")
            os.utime(old, (START.timestamp() - 86400, START.timestamp() - 86400))
            self.assertEqual(r._read_logs(os.path.join(d, "access.log*"), START)[1], [])

    def test_하나도_못_읽으면_실패(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(OSError):
                r._read_logs(os.path.join(d, "access.log*"), START)


class FakeSources:
    """원천 하나씩 죽여 볼 수 있는 가짜"""

    def __init__(self, budget_last=None, prom=PROM, partial_files=(), sync_events=(), **dead):
        self.dead = dead
        self.sync_events = list(sync_events)
        self.partial_files = list(partial_files)
        self.budget_last = END.timestamp() + 30 if budget_last is None else budget_last
        self.prom = prom

    def _check(self, name):
        if self.dead.get(name):
            raise OSError(f"{name} 읽기 실패(시험)")

    def access_lines(self, start):
        self._check("access")
        return self.access_list(), self.partial_files

    def access_list(self):
        return [access("203.0.113.1", utc(1), "/api/search?code=1"),
                access("203.0.113.1", utc(2), "/api/search?code=2"),
                access("198.51.100.9", utc(3), "/api/health")]

    def error_lines(self, start):
        self._check("error")
        return [], []

    def server_tz(self):
        return dt.timezone.utc

    def app_lines(self, start, end):
        self._check("app")
        return ["2026-11-15T01:00:00.000Z  INFO 1 --- [checkbook] [x] c : 통합 검색: code=1, standard=true"]

    def budget(self, day):
        self._check("db")
        return [("YES24", 950), ("DATANARU", 100)], self.budget_last

    def budget_sync_events(self, start):
        self._check("sync_log")
        return self.sync_events

    def prometheus(self):
        self._check("actuator")
        return {"green": r.parse_prometheus(self.prom)}


class ReportTest(unittest.TestCase):

    def test_모든_원천이_살아_있으면_표_한_행에_값이_다_있다(self):
        rep = r.build_report(DAY, FakeSources())
        row = r.markdown_row(rep)
        self.assertTrue(row.startswith("| 2026-11-15 | 2 |"))
        self.assertIn("5.0%", row)  # YES24 950 / 19000
        self.assertNotIn("수집 안 됨", row)
        self.assertEqual(row.count("|"), len(r.ROW_HEADER) + 1)

    def test_원천이_죽으면_값_대신_수집_안_됨을_찍고_나머지는_낸다(self):
        rep = r.build_report(DAY, FakeSources(db=True, access=True))
        text = r.render(rep)
        row = r.markdown_row(rep)
        self.assertIn("DB", rep.missing)
        self.assertIn("Nginx 접근 로그", rep.missing)
        self.assertIn("수집 안 됨", text)
        self.assertIn("db 읽기 실패(시험)", text)  # 왜 못 모았는지 보인다
        self.assertIn("수집 안 됨", row)
        self.assertIn("밀리", text)  # 앱 로그 쪽은 그대로 나온다

    def test_DB만_죽으면_예산_절에_수집_안_됨이_나온다(self):
        text = r.render(r.build_report(DAY, FakeSources(db=True)))
        budget = text.split("[예산]")[1].split("[제한]")[0]
        self.assertIn("수집 안 됨", budget.splitlines()[1])
        self.assertIn("동기화 실패(앱이 뜬 뒤): 0", budget)

    def test_액추에이터만_죽으면_DB_값은_그대로고_동기화_실패만_수집_안_됨(self):
        text = r.render(r.build_report(DAY, FakeSources(actuator=True)))
        budget = text.split("[예산]")[1].split("[제한]")[0]
        self.assertIn("yes24 950", budget)
        self.assertIn("동기화 실패(앱이 뜬 뒤): 수집 안 됨(액추에이터)", budget)

    def test_자정_뒤_기록이_없어도_오늘_쓴_양이_0이면_확정이다(self):
        # 화~일 아침: 새벽 예약 호출이 없고 사용자도 아직 없다
        rep = r.build_report(DAY, FakeSources(budget_last=END.timestamp() - 600,
                                              prom=PROM + 'external_budget_used{provider="yes24"} 0.0\n'))
        self.assertEqual(rep.budget.note, "")
        self.assertNotIn("미확정", r.markdown_row(rep))

    def test_자정_뒤_기록이_없는데_오늘_쓴_양이_있으면_미확정(self):
        rep = r.build_report(DAY, FakeSources(budget_last=END.timestamp() - 600,
                                              prom=PROM + 'external_budget_used{provider="yes24"} 12.0\n'))
        self.assertIn("미확정", r.markdown_row(rep))

    def test_일부_파일을_못_읽으면_정상이_아니라_일부로_표시한다(self):
        rep = r.build_report(DAY, FakeSources(partial_files=["access.log.1(PermissionError)"]))
        self.assertIn("Nginx 접근 로그: 일부만", r.render(rep))
        self.assertIn("Nginx 접근 로그(일부)", r.markdown_row(rep))

    def test_자정_뒤_기록이_있어도_동기화_실패가_있으면_미확정(self):
        # 행마다 저장하다 첫 실패에서 멈추므로 다른 공급자 행만 자정 뒤에 쓰였을 수 있다
        prom = PROM.replace("external_budget_sync_failures_total 0.0", "external_budget_sync_failures_total 2.0")
        rep = r.build_report(DAY, FakeSources(prom=prom))
        self.assertIn("미확정", r.markdown_row(rep))
        self.assertIn("동기화 실패 2회", r.render(rep))

    def test_액추에이터가_없으면_예산은_미확정(self):
        rep = r.build_report(DAY, FakeSources(actuator=True))
        self.assertIn("950 미확정", r.render(rep))

    def test_동기화_지표가_응답에_없으면_예산은_미확정(self):
        prom = PROM.replace("external_budget_sync_failures_total 0.0\n", "")
        rep = r.build_report(DAY, FakeSources(prom=prom))
        self.assertIn("응답에 동기화 실패 지표가 없음", rep.budget.note)

    def test_전날_실패_중_재시작해_카운터가_0이어도_종료_로그로_미확정(self):
        # CodeRabbit #87: 실패 중 종료하면 증가분을 버리고, 새 프로세스의 카운터는 0부터다
        restarted = PROM.replace("process_start_time_seconds 1.7631504E9",
                                 f"process_start_time_seconds {END.timestamp() + 3600}")
        rep = r.build_report(DAY, FakeSources(prom=restarted, sync_events=[
            "... WARN ... 공급자 예산 DB 동기화가 실패 중이라 종료 때 쓰지 않음 — 이번 칸의 미반영 증가분은 잃는다"]))
        self.assertIn("미확정", r.markdown_row(rep))
        self.assertIn("종료 때 미반영 로그 1줄", rep.budget.note)

    def test_0시_뒤_정상_배포는_확정(self):
        # 카운터만으로 보면 배포한 날마다 거짓 미확정이 된다 — 새 칸 시작 + 옛 칸 정상 종료면 빠진 몫이 없다
        restarted = PROM.replace("process_start_time_seconds 1.7631504E9",
                                 f"process_start_time_seconds {START.timestamp() + 3600}")
        rep = r.build_report(DAY, FakeSources(prom=restarted, sync_events=[
            "... INFO ... Started CheckbookApplication in 21.3 seconds (process running for 23.0)",
            "... INFO ... Graceful shutdown complete"]))
        self.assertEqual(rep.budget.note, "")

    def test_0시_뒤_강제_종료가_있었으면_미확정(self):
        # CodeRabbit #87: 실패가 0시 전에 시작되고 SIGKILL로 끝나면 종료 로그도 없이 증가분을 잃는다
        restarted = PROM.replace("process_start_time_seconds 1.7631504E9",
                                 f"process_start_time_seconds {START.timestamp() + 3600}")
        rep = r.build_report(DAY, FakeSources(prom=restarted, sync_events=[
            "... INFO ... Started CheckbookApplication in 21.3 seconds (process running for 23.0)"]))
        self.assertIn("앱 시작 1회에 정상 종료 0회", rep.budget.note)

    def test_0시_뒤에_떴는데_시작_로그가_없으면_미확정(self):
        restarted = PROM.replace("process_start_time_seconds 1.7631504E9",
                                 f"process_start_time_seconds {START.timestamp() + 3600}")
        rep = r.build_report(DAY, FakeSources(prom=restarted))
        self.assertIn("시작 로그가 없음", rep.budget.note)

    def test_전환_중이라_두_칸이_떠_있으면_옛_칸_종료는_아직_없어도_된다(self):
        both = {"blue": r.parse_prometheus(PROM),
                "green": r.parse_prometheus(PROM.replace("process_start_time_seconds 1.7631504E9",
                                                         f"process_start_time_seconds {START.timestamp() + 3600}"))}
        rep = r.Report(DAY, budget=r.summarize_budget([("YES24", 1)], END.timestamp() + 60, END),
                       metrics=r.summarize_metrics(both),
                       sync_events=["Started CheckbookApplication in 20 seconds"])
        r._check_budget(rep)
        self.assertEqual(rep.budget.note, "")

    def test_동기화_로그를_못_읽으면_예산은_미확정(self):
        rep = r.build_report(DAY, FakeSources(sync_log=True))
        self.assertIn("앱 로그로 동기화 실패를 확인 못 함", rep.budget.note)

    def test_IP_원문은_출력에_나오지_않는다(self):
        rep = r.build_report(DAY, FakeSources())
        out = r.render(rep) + r.markdown_row(rep)
        self.assertNotIn("203.0.113.1", out)
        self.assertNotIn("198.51.100.9", out)


if __name__ == "__main__":
    unittest.main()
