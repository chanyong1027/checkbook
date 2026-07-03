#!/usr/bin/env python3
"""poll-executor-metrics.sh가 만든 CSV를 시계열 PNG로 변환.
사용: python3 plot-metrics.py <csv경로> [png경로]
"""
import csv
import sys
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    path = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else path.replace(".csv", ".png")

    series = defaultdict(list)
    with open(path) as f:
        next(f)  # 헤더
        for row in csv.reader(f):
            if len(row) != 3:
                continue
            epoch, metric, value = row
            series[metric].append((int(epoch), float(value)))

    if not series:
        sys.exit("데이터 없음: " + path)

    fig, ax = plt.subplots(figsize=(12, 6))
    t0 = min(points[0][0] for points in series.values())
    # 수집 실패 시점은 시리즈가 아니라 수직선으로 표시 — 직선 보간이 결측을 은폐하지 않게
    fails = series.pop("scrape_failed", [])
    for ts, _ in fails:
        ax.axvline(ts - t0, color="red", alpha=0.4, linewidth=0.8)
    for name, points in sorted(series.items()):
        xs = [p[0] - t0 for p in points]
        ys = [p[1] for p in points]
        ax.plot(xs, ys, label=name, linewidth=1.2)
    ax.set_xlabel("elapsed (s)")
    ax.set_ylabel("value")
    ax.legend(fontsize=7, loc="upper left")
    ax.grid(True, alpha=0.3)
    fig.savefig(out, dpi=150, bbox_inches="tight")
    print(out)


if __name__ == "__main__":
    main()
