"""규칙 원본·생성기 시험. 식의 뜻(블루·그린, 처음 생기는 시리즈 등)은 promtool 시험(rules_test.yaml)이 본다"""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import rules  # noqa: E402

DATASOURCES = {"prom": "prom-uid", "loki": "loki-uid"}
RULES = rules.build_rules()


class RuleSourceTest(unittest.TestCase):

    def test_uid는_겹치지_않고_Grafana가_받는_모양이다(self):
        uids = [r.uid for r in RULES]
        self.assertEqual(len(uids), len(set(uids)))
        for uid in uids:
            self.assertRegex(uid, r"^[a-zA-Z0-9_-]{1,40}$")

    def test_규칙마다_대응_안내가_있다(self):
        for r in RULES:
            self.assertTrue(r.summary and r.runbook, r.uid)

    def test_앱_지표_끊김만_데이터_없음을_알림으로_본다(self):
        # 나머지는 조건을 넘는 시리즈만 남기는 식이라 비어 있는 게 정상이다
        no_data_alerting = [r.uid for r in RULES if r.no_data == "Alerting"]
        self.assertEqual(no_data_alerting, ["cb-app-down"])

    def test_요약의_라벨은_식이_남기는_라벨이다(self):
        # {{ $labels.section }}을 쓰는데 식이 section으로 묶지 않으면 Slack에 빈 값이 나간다
        for r in RULES:
            for label in re.findall(r"\$labels\.(\w+)", r.summary + " ".join(r.runbook)):
                kept = (re.search(rf"by \([^)]*\b{label}\b", r.expr)
                        or f'"{label}", "' in r.expr  # label_replace로 단 라벨
                        # 묶지 않는 서버 규칙은 node_filesystem 시리즈의 라벨이 그대로 남는다
                        or (label == "mountpoint" and "node_filesystem" in r.expr and "sum" not in r.expr))
                self.assertTrue(kept, f"{r.uid}: $labels.{label}")

    def test_사라짐_감시는_규칙이_보는_앱_지표를_다_덮는다(self):
        missing = next(r for r in RULES if r.uid == "cb-app-metrics-missing")
        watched = set(re.findall(r"absent\((\w+)\{", missing.expr))
        self.assertEqual(watched, set(re.findall(r'"metric", "(\w+)"', missing.expr)), "라벨과 지표 이름이 짝이 맞아야 한다")
        lazy = {m for r in RULES for m in r.lazy_metrics}
        for r in RULES:
            if r.datasource != "prom" or r is missing:
                continue
            for m in rules.metric_names(r.expr):
                if m == "up" or m.startswith("node_"):
                    continue
                self.assertTrue(m in watched or m in lazy, f"{r.uid}: {m}가 감시에서 빠짐(평소 없으면 lazy_metrics에)")

    def test_비율_규칙은_대상마다_최소_건수_조건이_있다(self):
        r = next(r for r in RULES if r.uid == "cb-section-failed-burn")
        for section in ("PUBLIC_LIBRARY", "NEW_BOOK", "SUBSCRIPTION"):
            self.assertRegex(r.expr, rf'section="{section}"\}}\[1h\]\)\) >= \d+')
            self.assertRegex(r.expr, rf'section="{section}"\}}\[6h\]\)\) >= \d+')
        self.assertNotIn("USED_BOOK", r.expr)

    def test_5xx_분모에서_합성_요청을_뺀다(self):
        r = next(r for r in RULES if r.uid == "cb-server-5xx-burn")
        self.assertIn('uri=~"/api/.+", uri!="/api/health"', r.expr)
        self.assertNotIn("actuator", r.expr)

    def test_조건을_넘은_값이_0이_되는_규칙이_없다(self):
        # 조건 B가 'A > 0'이라 A의 값이 0이면 조건을 넘었는데도 안 울린다(디스크 여유 0%가 그랬다)
        disk = [r for r in RULES if "node_filesystem" in r.expr]
        self.assertTrue(disk)
        for r in disk:
            self.assertIn("100 - node_filesystem_avail_bytes", r.expr, f"{r.uid}: 값은 사용 비율이어야 한다")

    def test_예산_게이지는_칸끼리_더하지_않는다(self):
        # 각 칸이 DB 합계를 반영해 이미 전체 사용량이라 sum이면 2배가 된다
        for uid in ("cb-budget-blocked", "cb-budget-80"):
            r = next(r for r in RULES if r.uid == uid)
            self.assertNotRegex(r.expr, r"sum[^(]*\(external_budget")
            self.assertIn("max by (provider) (external_budget_used", r.expr)


class GrafanaJsonTest(unittest.TestCase):

    def setUp(self):
        self.groups = rules.grafana_groups("folder-uid", DATASOURCES)

    def test_등급마다_묶음_하나와_연락처(self):
        self.assertEqual(set(self.groups), {"checkbook-urgent", "checkbook-info"})
        receivers = {g: {r["notification_settings"]["receiver"] for r in body["rules"]}
                     for g, body in self.groups.items()}
        self.assertEqual(receivers, {"checkbook-urgent": {"slack-urgent"}, "checkbook-info": {"slack-info"}})
        self.assertEqual(sum(len(b["rules"]) for b in self.groups.values()), len(RULES))

    def test_규칙_모양(self):
        for body in self.groups.values():
            self.assertEqual(body["interval"], 60)
            for r in body["rules"]:
                self.assertEqual(r["folderUID"], "folder-uid")
                self.assertEqual(r["ruleGroup"], body["title"])
                self.assertEqual(r["condition"], "B")
                self.assertEqual(r["execErrState"], "Error", "깨진 질의가 조용히 넘어가면 안 된다")
                query, condition = r["data"]
                self.assertEqual(condition["datasourceUid"], "__expr__")
                self.assertEqual(condition["model"]["expression"], "A")
                self.assertIn(query["datasourceUid"], DATASOURCES.values())

    def test_로그_규칙은_Loki_데이터_소스(self):
        error_log = next(r for b in self.groups.values() for r in b["rules"] if r["uid"] == "cb-error-log")
        self.assertEqual(error_log["data"][0]["datasourceUid"], "loki-uid")
        self.assertEqual(error_log["data"][0]["model"]["queryType"], "instant")


class PromtoolTest(unittest.TestCase):

    def test_같은_식에_조건을_붙이고_로그_규칙은_뺀다(self):
        out = rules.promtool_rules()["groups"][0]["rules"]
        by_uid = {r["alert"]: r for r in out}
        self.assertNotIn("cb-error-log", by_uid)
        self.assertEqual(len(out), len([r for r in RULES if r.datasource == "prom"]))
        app_down = next(r for r in RULES if r.uid == "cb-app-down")
        self.assertEqual(by_uid["cb-app-down"]["expr"], f"({app_down.expr}) < 1")
        self.assertEqual(by_uid["cb-app-down"]["for"], "3m")


if __name__ == "__main__":
    unittest.main()
