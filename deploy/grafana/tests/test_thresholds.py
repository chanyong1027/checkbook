"""문턱 계산 시험. 실행: python3 -m unittest discover -s deploy/grafana/tests"""
import math
import os
import random
import sys
import unittest
from fractions import Fraction

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import thresholds  # noqa: E402


def exact_tail(k, n, p):
    p = Fraction(p).limit_denominator(10**6)
    return float(sum(math.comb(n, i) * p**i * (1 - p) ** (n - i) for i in range(max(k, 0), n + 1)))


class TailTest(unittest.TestCase):

    def test_분수로_끝까지_더한_값과_같다(self):
        # 꼬리 합을 일찍 멈추는 최적화가 값을 바꾸지 않았는지
        rng = random.Random(1)
        for _ in range(300):
            n = rng.randint(1, 300)
            k = rng.randint(0, n + 1)
            p = rng.choice([0.003, 0.01, 0.0289, 0.144, 0.5])
            expected = exact_tail(k, n, p)
            self.assertAlmostEqual(thresholds.tail(k, n, p), expected, delta=max(expected * 1e-9, 1e-15),
                                   msg=f"k={k} n={n} p={p}")

    def test_경계(self):
        self.assertEqual(thresholds.tail(0, 10, 0.1), 1.0)
        self.assertEqual(thresholds.tail(11, 10, 0.1), 0.0)
        self.assertEqual(thresholds.tail(1, 10, 0.0), 0.0)


class ClopperPearsonTest(unittest.TestCase):

    def test_실패_0이면_3_나누기_n_규칙과_거의_같다(self):
        self.assertAlmostEqual(thresholds.clopper_pearson_upper(0, 1000), 3 / 1000, delta=1e-4)

    def test_상한에서_관측값_이하가_나올_확률이_5퍼센트(self):
        p = thresholds.clopper_pearson_upper(20, 1000)
        self.assertAlmostEqual(1 - thresholds.tail(21, 1000, p), 0.05, delta=1e-6)


class MinimumCountTest(unittest.TestCase):

    def test_돌려준_최소_건수가_가장_작고_그_이상_모든_n에서_예산_안(self):
        for ratio, p, budget in [(0.144, 0.01, 2.98e-3), (0.06, 0.0289, 1.79e-2), (0.144, 0.0169, 2.98e-3)]:
            n_min, worst = thresholds.minimum_count(ratio, p, budget)
            self.assertLess(worst, budget)
            suffix = thresholds.false_alarm_suffix_max(ratio, p)
            self.assertGreaterEqual(suffix[n_min - 1], budget, "하나 작은 값도 예산 안이면 최소가 아니다")

    def test_오탐_예산은_등급_전체를_알림원_수로_나눈다(self):
        # 즉시: 5xx 1h·6h, 낮: 섹션 3 × 2 + 공급자 4 × 2
        self.assertEqual(thresholds.sources_per_tier(), {"urgent": 2, "info": 14})

    def test_결정_카드에_적은_값(self):
        # 이 값이 바뀌면(기준선·예산·SLO 변경) 결정 카드 'SLO' 절도 같이 고친다
        self.assertEqual(thresholds.minimum_counts(), {
            "server_5xx_fast": {"server": 14},
            "server_5xx_slow": {"server": 34},
            "section_failed_fast": {"PUBLIC_LIBRARY": 14, "NEW_BOOK": 28, "SUBSCRIPTION": 14},
            "section_failed_slow": {"PUBLIC_LIBRARY": 34, "NEW_BOOK": 184, "SUBSCRIPTION": 34},
            "client_slow_fast": {"datanaru": 21, "millie": 21, "kakao": 21, "yes24": 21},
            "client_slow_slow": {"datanaru": 67, "millie": 67, "kakao": 67, "yes24": 84},
        })


if __name__ == "__main__":
    unittest.main()
