#!/usr/bin/env python3
"""알림 비율 규칙의 최소 건수를 계산한다(PR-B 결정 카드 2026-10-19 '카드 2').

비율 문턱은 Google SRE Workbook 'Alerting on SLOs'의 다중 창 소진율 표에서 가져온다(SLO 99% 가정).
  소진율 14.4 → 오류 비율 14.4%(긴 창 1시간 + 짧은 창 5분), 소진율 6 → 6%(6시간 + 30분)
최소 건수는 정상일 때의 실패율 p에서 이항분포로 정한다:
  긴 창 안 요청 수 n ≥ 최소 건수인 모든 n에서 P(X ≥ ⌈r·n⌉ | n, p) < 창당 오탐 예산
  창당 오탐 예산 = 기간당 허용 오탐 ÷ (기간 ÷ 긴 창). 즉시는 주 1회, 낮은 하루 1회
p는 '정상'의 상한으로 둔다: 실측 실패 x/n의 95% Clopper-Pearson 상한과 SLO 허용치(1%) 중 큰 값.
SLO를 딱 지키고 있는 서비스(p = 1%)가 알림을 울리면 오탐이라는 뜻이다.

한계: 매분 평가하는 창은 서로 겹쳐 독립이 아니고, 실패도 독립이 아니다(공급자 장애는 몰려서 온다).
그래서 오탐 예산은 근사다 — 사이트 알림 뒤 2주 검토 때 실제 오탐 수로 다시 맞춘다.

표준 라이브러리만 쓴다. 실행: python3 deploy/grafana/thresholds.py (계산 근거 표 출력)
규칙 생성기(rules.py)는 minimum_counts()를 불러 이 값을 그대로 쓴다 — 기준선을 바꾸면 규칙도 같이 바뀐다.
"""
import math
import sys

SLO_ERROR_BUDGET = 0.01   # SLO 99%
MINUTES = {"1h": 60, "6h": 360, "day": 1440, "week": 10080}
N_SEARCH_MAX = 2000       # 이 위로는 확률이 더 작아질 뿐이라 보지 않는다(p ≤ 3%, r ≥ 6%면 n이 클수록 0에 수렴)


def tail(k, n, p):
    """P(X ≥ k), X ~ 이항(n, p)"""
    if k <= 0:
        return 1.0
    if k > n:
        return 0.0
    if p <= 0:
        return 0.0
    if p >= 1:
        return 1.0
    if k <= n * p:
        # 꼬리가 평균을 넘지 않는 쪽이면 1 - 아래쪽 합(아래쪽은 항이 줄어드는 방향이라 짧다)
        return 1.0 - _sum_terms(0, k - 1, n, p)
    return _sum_terms(k, n, n, p)


def _sum_terms(lo, hi, n, p):
    """Σ_{i=lo..hi} C(n,i) p^i (1-p)^(n-i). 평균(n·p)에서 먼 쪽부터 줄어드는 항은 충분히 작아지면 멈춘다"""
    def log_term(i):
        return (math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1)
                + i * math.log(p) + (n - i) * math.log1p(-p))

    # 위쪽 꼬리(lo > 평균)는 i가 커질수록, 아래쪽(hi < 평균)은 i가 작아질수록 항이 줄어든다
    upward = lo > n * p
    order = range(lo, hi + 1) if upward else range(hi, lo - 1, -1)
    total = 0.0
    for i in order:
        term = math.exp(log_term(i))
        total += term
        if term < total * 1e-15:
            break
    return min(1.0, total)


def clopper_pearson_upper(x, n, confidence=0.95):
    """실패 x/n일 때 실패율의 한쪽 95% 상한. x = 0이면 '3/n 규칙'과 거의 같다"""
    alpha = 1 - confidence
    lo, hi = x / n, 1.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if 1 - tail(x + 1, n, mid) > alpha:   # P(X ≤ x | mid) > alpha면 더 올릴 수 있다
            lo = mid
        else:
            hi = mid
    return hi


def window_budget(alarms, period, window):
    return alarms / (MINUTES[period] / MINUTES[window])


def false_alarm_suffix_max(ratio, p):
    """[n] = n 이상 가운데 오탐 확률의 최댓값. 비율 문턱은 ⌈r·n⌉건으로 바뀌어 n에 따라 들쭉날쭉하므로
    '최소 건수 이상 모든 n'을 보려면 뒤에서부터 최댓값을 누적한다"""
    suffix = [0.0] * (N_SEARCH_MAX + 2)
    for n in range(N_SEARCH_MAX, 0, -1):
        suffix[n] = max(suffix[n + 1], tail(math.ceil(ratio * n - 1e-9), n, p))
    return suffix


def minimum_count(ratio, p, budget):
    """(최소 건수, 그 이상에서의 최악 오탐 확률)"""
    suffix = false_alarm_suffix_max(ratio, p)
    for n_min in range(1, N_SEARCH_MAX + 1):
        if suffix[n_min] < budget:
            return n_min, suffix[n_min]
    raise ValueError(f"최소 건수를 못 찾음: r={ratio}, p={p}")


# 정상 실패율 근거: T4 지연 탐침(2026-10-19, OCI 서버, 공급자당 1,000회)
#  실패: 4,000회 중 0건. YES24만 지금 설정(연결 200ms·전체 700ms)으로 20건(2.0%)
#  1.07초 넘음(외부 호출 히스토그램 구간 le=1.073741824): YES24 ≤ 12건(전체 > 700ms 12건),
#  나머지는 p99 < 1.07초라 ≤ 10건으로 보수적으로 잡는다. 탐침은 매번 새 연결이라 앱(연결 재사용)보다 비관적이다
BASELINES = {
    "failure": {"datanaru": (0, 1000), "millie": (0, 1000), "kakao": (0, 1000), "yes24": (20, 1000)},
    "slow": {"datanaru": (10, 1000), "millie": (10, 1000), "kakao": (10, 1000), "yes24": (12, 1000)},
}

# 검색 섹션 → 기대는 공급자. USED_BOOK은 링크만 만들어 실패하지 않으므로 뺀다
SECTIONS = {
    "PUBLIC_LIBRARY": BASELINES["failure"]["datanaru"],
    "NEW_BOOK": BASELINES["failure"]["yes24"],
    "SUBSCRIPTION": BASELINES["failure"]["millie"],
}

# 등급별 오탐 예산(10-19 결정): 즉시는 주 1회, 낮은 하루 1회 — 등급 전체의 합이다
TIER_BUDGET = {"urgent": ("week", 1), "info": ("day", 1)}

# SRE Workbook 표: 1시간에 한 달(720시간) 예산의 2% → 소진율 14.4, 6시간에 5% → 6
FAST = round(14.4 * SLO_ERROR_BUDGET, 6)
SLOW = round(6 * SLO_ERROR_BUDGET, 6)

# (이름, 등급, 비율, 긴 창, 대상). 대상 값이 None이면 실측 기준선 없음 → SLO 허용치를 정상 실패율로 본다
RULES = [
    ("server_5xx_fast", "urgent", FAST, "1h", {"server": None}),
    ("server_5xx_slow", "urgent", SLOW, "6h", {"server": None}),
    ("section_failed_fast", "info", FAST, "1h", SECTIONS),
    ("section_failed_slow", "info", SLOW, "6h", SECTIONS),
    ("client_slow_fast", "info", FAST, "1h", BASELINES["slow"]),
    ("client_slow_slow", "info", SLOW, "6h", BASELINES["slow"]),
]


def sources_per_tier():
    """알림이 따로 울릴 수 있는 곳(규칙 × 대상) 수. 예산을 규칙마다 통째로 주면 규칙이 늘수록 오탐도 는다 —
    등급 예산을 알림원 수로 똑같이 나눈다(다중 비교의 Bonferroni 보정과 같은 생각)"""
    counts = {}
    for _, tier, _, _, targets in RULES:
        counts[tier] = counts.get(tier, 0) + len(targets)
    return counts


def compute():
    sources = sources_per_tier()
    rows = []
    for name, tier, ratio, window, targets in RULES:
        period, alarms = TIER_BUDGET[tier]
        budget = window_budget(alarms / sources[tier], period, window)
        per_target = {}
        for target, observed in targets.items():
            measured = clopper_pearson_upper(*observed) if observed else 0.0
            p = max(measured, SLO_ERROR_BUDGET)
            n_min, worst = minimum_count(ratio, p, budget)
            # 실패율 50%인 장애를 최소 건수 창에서 잡을 확률(감지력)
            detect = tail(math.ceil(ratio * n_min - 1e-9), n_min, 0.5)
            per_target[target] = (p, n_min, worst, detect)
        rows.append((name, ratio, window, period, budget, per_target))
    return rows


def minimum_counts():
    """{규칙 이름: {대상: 최소 건수}} — 규칙 생성기(rules.py)가 이 값을 그대로 쓴다(숫자를 두 곳에 두지 않음)"""
    return {name: {target: v[1] for target, v in per_target.items()}
            for name, _, _, _, _, per_target in compute()}


def ratio_of(rule_name):
    for name, _, ratio, _, _ in RULES:
        if name == rule_name:
            return ratio
    raise KeyError(rule_name)


def main():
    rows = compute()
    for name, ratio, window, period, budget, per_target in rows:
        print(f"{name}: 비율 ≥ {ratio:.1%}, 긴 창 {window}, 창당 오탐 예산 {budget:.2e} (등급 예산 1회/{period} ÷ 알림원 수)")
        for target, (p, n_min, worst, detect) in per_target.items():
            print(f"  {target:9s} p={p:.4f}  최소 건수 {n_min:3d}  최악 오탐 {worst:.2e}  실패 50% 감지 {detect:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
