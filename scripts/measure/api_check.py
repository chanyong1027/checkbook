#!/usr/bin/env python3
"""순서 4: 지역 조회(libSrchByBook) API 확인과 갈림 규칙 판정 자료 모으기 — 운영 서버에서 실행한다.

결정 카드 B2의 갈림 규칙 셋과 D1의 응답 꼴을 구현 전에 실제 정보나루로 확인한다(규칙: 측정 규칙 문서 6절, 11-21 고침).
  A. 응답 꼴(D1): 가장 큰 시도(경기) 흔한 책 1권으로 한 번에 받기(pageSize 1000)·기본 페이지·100씩 나눠 받기를 비교
  B. 규칙 1·2: 위치마다 10km 원이 걸치는 시도를 병렬로 부르는 "지역 조회 단계" — 호출 수와 걸린 시간(흔한 책 5권 × 3회)
  C. 규칙 3: 시도 목록마다 기준 위치에서 목록에 없는 가까운 도서관 5곳을 bookExist로 확인(18권, 거짓 "없음" 찾기)

서버에서 실행하는 이유: 운영 서버(OCI 오사카)가 정보나루를 부르는 시간이 규칙 2의 대상이다. 측정 PC에서 재면 경로가 다르다.

    D=$(mktemp -d)
    sudo git -C /opt/checkbook/app fetch -q origin
    sudo git -C /opt/checkbook/app show origin/main:scripts/measure/api_check.py > "$D/api_check.py"
    sudo git -C /opt/checkbook/app show origin/main:scripts/measure/plan.json > "$D/plan.json"
    sudo python3 "$D/api_check.py" --plan "$D/plan.json" --out "$D/api-check.jsonl"

표준 라이브러리만 쓴다. 키는 /opt/checkbook/.env의 DATANARU_AUTH_KEY에서 읽고 어디에도 남기지 않는다.
응답 원문은 남기지 않는다(도서관 코드·건수·시간만). 정보나루는 초당 2회 미만, 한 조회 안의 시도 병렬(최대 3)만 겹친다.
이 호출은 앱의 공급자 예산(ExternalCallBudget)에 잡히지 않는다(같은 키를 쓰므로 정보나루 쪽 하루 사용량에는 들어간다).
"""
import argparse
import concurrent.futures
import json
import math
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://data4library.kr/api"
BIG_PAGE = 1000
GAP_SECONDS = 0.6  # 차례로 부를 때 간격(초당 2회 미만)
LOOKUP_GAP_SECONDS = 1.0  # 지역 조회 단계 사이 간격
ROUNDS = 3
NEAREST_ABSENT = 5
TIMEOUT_SECONDS = 15


def load_key(env_path):
    with open(env_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("DATANARU_AUTH_KEY="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("DATANARU_AUTH_KEY 없음")


class Client:
    def __init__(self, key):
        self._key = key
        self._lock = threading.Lock()
        self.calls = 0

    def get(self, path, **params):
        """(response 칸 dict 또는 None, 걸린 초, 오류 설명 또는 None). 예외를 밖으로 던지지 않는다"""
        with self._lock:
            self.calls += 1
        q = urllib.parse.urlencode({"authKey": self._key, "format": "json", **params})
        req = urllib.request.Request(f"{BASE}/{path}?{q}", headers={"User-Agent": "checkbook-measure"})
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as r:
                body = r.read()
                status = r.status
        except urllib.error.HTTPError as e:
            return None, time.monotonic() - t0, f"HTTP {e.code}"
        except Exception as e:  # 연결 실패·시간 초과 — 원인 이름만
            return None, time.monotonic() - t0, type(e).__name__
        elapsed = time.monotonic() - t0
        try:
            resp = json.loads(body).get("response")
        except ValueError:
            return None, elapsed, f"JSON 아님(HTTP {status})"
        if not isinstance(resp, dict):
            return None, elapsed, "response 칸 없음"
        if resp.get("error"):
            # 정보나루는 키 오류·한도 초과를 HTTP 200 + error 칸으로 준다(DatanaruClient 주석)
            return resp, elapsed, f"error 칸: {str(resp.get('error'))[:80]}"
        return resp, elapsed, None


def lib_codes(resp):
    return [l["lib"]["libCode"] for l in (resp or {}).get("libs", []) if isinstance(l, dict) and "lib" in l]


def as_int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def fetch_region(client, isbn, region, page_size=BIG_PAGE):
    """한 시도를 끝까지 받는다. 첫 쪽의 numFound로 쪽 수를 정하고 나머지 쪽은 차례로(드물다)"""
    pages = []
    codes = []
    resp, el, err = client.get("libSrchByBook", isbn=isbn, region=region, pageNo=1, pageSize=page_size)
    num_found = as_int((resp or {}).get("numFound"))
    pages.append({"pageNo": 1, "sec": round(el, 3), "error": err, "numFound": num_found,
                  "resultNum": as_int((resp or {}).get("resultNum")), "got": len(lib_codes(resp))})
    codes += lib_codes(resp)
    if err is None and num_found:
        need = math.ceil(num_found / page_size)
        for page in range(2, need + 1):
            time.sleep(GAP_SECONDS)
            r2, el2, err2 = client.get("libSrchByBook", isbn=isbn, region=region, pageNo=page, pageSize=page_size)
            pages.append({"pageNo": page, "sec": round(el2, 3), "error": err2, "numFound": as_int((r2 or {}).get("numFound")),
                          "resultNum": as_int((r2 or {}).get("resultNum")), "got": len(lib_codes(r2))})
            codes += lib_codes(r2)
    distinct = sorted(set(codes))
    found_values = {p["numFound"] for p in pages}
    # D1 저장 조건: 모든 쪽 오류 없음, 쪽마다 numFound 같음, 중복 뺀 코드 수 = numFound
    complete = all(p["error"] is None for p in pages) and len(found_values) == 1 and num_found == len(distinct)
    return {"region": region, "pages": pages, "numFound": num_found, "distinct": len(distinct),
            "duplicates": len(codes) - len(distinct), "complete": complete}, distinct


def region_stage(client, isbn, regions):
    """후 구조의 지역 조회 단계: 걸치는 시도를 병렬로 부르고 모두 끝날 때까지의 시간. (초, 시도별 결과, 받은 코드 합집합)"""
    t0 = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(regions)) as ex:
        pairs = list(ex.map(lambda r: fetch_region(client, isbn, r), regions))
    stage = time.monotonic() - t0
    return stage, [res for res, _ in pairs], set().union(*[set(codes) for _, codes in pairs])


def haversine(a, b, c, d):
    r = 6371.0
    p1, p2 = math.radians(a), math.radians(c)
    dp, dl = p2 - p1, math.radians(d - b)
    return 2 * r * math.asin(math.sqrt(math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2))


def emit(out, rec):
    out.write(json.dumps(rec, ensure_ascii=False) + "\n")
    out.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", required=True)
    ap.add_argument("--env", default="/opt/checkbook/.env")
    ap.add_argument("--out", required=True)
    ap.add_argument("--phases", default="ABC")
    ap.add_argument("--limit", type=int, default=0, help="스크립트 확인용: 목록마다 앞에서 N권, B는 1회만(판정에 쓰지 않는다)")
    args = ap.parse_args()
    plan = json.load(open(args.plan, encoding="utf-8"))
    client = Client(load_key(args.env))
    notice = plan["noticeInstitutions"]["names"]
    out = open(args.out, "w", encoding="utf-8")
    started = time.time()
    emit(out, {"phase": "start", "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "limit": args.limit})

    if "A" in args.phases:
        isbn = next(b["isbn"] for b in plan["books"]["gyeonggi"] if b["bin"] == "common")
        one, one_codes = fetch_region(client, isbn, 31, BIG_PAGE)
        time.sleep(GAP_SECONDS)
        resp, el, err = client.get("libSrchByBook", isbn=isbn, region=31)  # pageSize 없이: 기본 쪽 크기
        default = {"sec": round(el, 3), "error": err, "numFound": as_int((resp or {}).get("numFound")),
                   "pageSize": as_int((resp or {}).get("pageSize")), "resultNum": as_int((resp or {}).get("resultNum"))}
        time.sleep(GAP_SECONDS)
        split, split_codes = fetch_region(client, isbn, 31, 100)
        emit(out, {"phase": "A", "isbn": isbn, "region": 31, "onePage": one, "defaultPage": default, "split100": split,
                   "sameCodes": one_codes == split_codes})

    if "B" in args.phases:
        for rnd in range(1, (1 if args.limit else ROUNDS) + 1):
            for key, pos in plan["positions"].items():
                commons = [b for b in plan["books"][pos["books"]] if b["bin"] == "common"][: args.limit or None]
                for b in commons:
                    time.sleep(LOOKUP_GAP_SECONDS)
                    stage, results, _ = region_stage(client, b["isbn"], pos["regions"])
                    emit(out, {"phase": "B", "round": rnd, "position": key, "isbn": b["isbn"], "regions": pos["regions"],
                               "stageSec": round(stage, 3), "calls": sum(len(r["pages"]) for r in results), "perRegion": results})

    if "C" in args.phases:
        # 도서관 좌표(앱 DB와 같은 출처: libSrch 전체). 시도 넷
        libs = {}
        for region in sorted({r for p in plan["positions"].values() for r in p["regions"]} | set(plan["bookRegions"].values())):
            time.sleep(GAP_SECONDS)
            resp, el, err = client.get("libSrch", region=region, pageNo=1, pageSize=BIG_PAGE)
            if err:
                emit(out, {"phase": "C", "error": f"libSrch {region}: {err}"})
                continue
            if as_int(resp.get("numFound")) != len(resp.get("libs", [])):
                emit(out, {"phase": "C", "warn": f"libSrch {region}: numFound {resp.get('numFound')} 중 {len(resp.get('libs', []))}곳만 받음"})
            for l in resp.get("libs", []):
                lib = l.get("lib", {})
                try:
                    libs[lib["libCode"]] = (lib.get("libName", ""), float(lib["latitude"]), float(lib["longitude"]), region)
                except (KeyError, TypeError, ValueError):
                    pass
        anchors = {"seoul": "seoul-cityhall", "gyeonggi": "ansan-cityhall", "gangwon": "wonju-cityhall"}
        for list_key, pos_key in anchors.items():
            pos = plan["positions"][pos_key]
            nearest = sorted((haversine(pos["lat"], pos["lon"], la, lo), code, name)
                             for code, (name, la, lo, region) in libs.items() if region in pos["regions"])
            books = plan["books"][list_key]
            if args.limit:  # 묶음마다 앞에서 N권
                books = [b for bin_ in ("common", "middle", "rare", "absent") for b in [x for x in books if x["bin"] == bin_][: args.limit]]
            for b in books:
                time.sleep(LOOKUP_GAP_SECONDS)
                _, results, listed = region_stage(client, b["isbn"], pos["regions"])
                complete = all(r["complete"] for r in results)
                checks = []
                if complete:
                    # 목록에 없는 가까운 도서관 5곳 — 목록이 맞다면 모두 "없음"이어야 한다
                    for dist, code, name in [x for x in nearest if x[1] not in listed][:NEAREST_ABSENT]:
                        time.sleep(GAP_SECONDS)
                        resp, el, err = client.get("bookExist", libCode=code, isbn13=b["isbn"])
                        result = (resp or {}).get("result") or {}
                        has = str(result.get("hasBook", "")).strip().upper() or None
                        checks.append({"libCode": code, "name": name, "km": round(dist, 2), "sec": round(el, 3), "error": err,
                                       "hasBook": has, "falseAbsent": has == "Y",
                                       "notice": any(n in name for n in notice)})
                emit(out, {"phase": "C", "list": list_key, "position": pos_key, "isbn": b["isbn"], "bin": b["bin"],
                           "listComplete": complete, "listed": len(listed), "perRegion": results, "checks": checks})
    out.close()
    print(f"끝: 정보나루 {client.calls}회, {time.time() - started:.0f}초 → {args.out}", file=sys.stderr)


# --- 판정(측정 PC에서): python3 scripts/measure/api_check.py --summarize build/measure/api-check/<파일>.jsonl ---
RULE1_MAX_CALLS = 4  # 지역 조회 호출 + 대출 확인 5 ≤ 9.5
RULE2_TARGET_SEC = 1.5  # 첫 표시 2초 − 지역 조회 밖 몫 0.5초 [가정]
RULE2_SHARE = 0.95  # D3: 95% 이상 기준 이하 + 가장 느린 값 기준의 2배 이하


def summarize(path):
    recs = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    start = next((r for r in recs if r.get("phase") == "start"), {})
    out = [f"# 순서 4 API 확인 판정 — `{path}`", "", f"- 실행 {start.get('at')}" + (f" ⚠ --limit {start['limit']}(스크립트 확인용, 판정에 쓰지 않음)" if start.get("limit") else "")]
    a = next((r for r in recs if r.get("phase") == "A"), None)
    if a:
        out += ["", "## 응답 꼴(D1)", f"- 한 번에 받기(pageSize {BIG_PAGE}): numFound {a['onePage']['numFound']}, 받은 코드 {a['onePage']['distinct']}, 쪽 {len(a['onePage']['pages'])}, 완전 {a['onePage']['complete']}",
                f"- pageSize 없이: 쪽 크기 {a['defaultPage']['pageSize']}, resultNum {a['defaultPage']['resultNum']}",
                f"- 100씩 나눠 받기: 쪽 {len(a['split100']['pages'])}, 완전 {a['split100']['complete']}, 한 번에 받은 것과 코드가 같음 {a['sameCodes']}"]
    b = [r for r in recs if r.get("phase") == "B"]
    lists = [x for r in recs if r.get("phase") in ("B", "C") for x in r.get("perRegion", [])]
    incomplete = [x for x in lists if not x["complete"]]
    out += [f"- 지역 조회 시도 응답 {len(lists)}건 중 불완전(오류·쪽마다 numFound 다름·코드 수 ≠ numFound) {len(incomplete)}건"]
    for x in incomplete[:10]:
        out.append(f"  - 시도 {x['region']}: numFound {x['numFound']}, 받은 코드 {x['distinct']}, 쪽 오류 {[p['error'] for p in x['pages'] if p['error']]}")
    if b:
        calls = [r["calls"] for r in b]
        secs = sorted(r["stageSec"] for r in b)
        within = sum(1 for v in secs if v <= RULE2_TARGET_SEC) / len(secs)
        r1 = "그대로" if max(calls) <= RULE1_MAX_CALLS else "멈춤(먼저 pageSize로 줄일 수 있는지)"
        r2 = "그대로" if within >= RULE2_SHARE and secs[-1] <= RULE2_TARGET_SEC * 2 else "멈춤"
        out += ["", "## 규칙 1·2 (위치마다 흔한 책, 걸치는 시도를 병렬로)", "", "| 위치 | 시도 | 조회 | 호출 수 | 단계 초(최소~최대) |", "|---|---|---|---|---|"]
        for key in dict.fromkeys(r["position"] for r in b):
            rs = [r for r in b if r["position"] == key]
            ss = [r["stageSec"] for r in rs]
            out.append(f"| {key} | {rs[0]['regions']} | {len(rs)} | {min(r['calls'] for r in rs)}~{max(r['calls'] for r in rs)} | {min(ss):.2f}~{max(ss):.2f} |")
        out += ["", f"- 규칙 1(호출 ≤ {RULE1_MAX_CALLS}): 최대 {max(calls)} → **{r1}**",
                f"- 규칙 2(단계 ≤ {RULE2_TARGET_SEC}초, 95% + 최대 {RULE2_TARGET_SEC * 2}초): {len(secs)}건 중 {within:.1%} 이하, 최대 {secs[-1]:.2f}초 → **{r2}**"]
    c = [r for r in recs if r.get("phase") == "C" and "checks" in r]
    if c:
        checks = [x for r in c for x in r["checks"]]
        false_other = [(r["list"], r["isbn"], x) for r in c for x in r["checks"] if x["falseAbsent"] and not x["notice"]]
        false_notice = [x for x in checks if x["falseAbsent"] and x["notice"]]
        errors = [x for x in checks if x["error"] or x["hasBook"] not in ("Y", "N")]
        skipped = [r for r in c if not r["listComplete"]]
        n = len(checks) - len(errors)
        upper = 1 - 0.05 ** (1 / n) if n else None
        r3 = "멈춤(원인을 적는다)" if false_other else ("판정 보류(확인 못 한 칸·불완전 목록)" if errors or skipped else "그대로")
        out += ["", "## 규칙 3 (목록에 없는 가까운 도서관이 정말 없는가)",
                f"- 확인 {len(checks)}곳(책 {len(c)}권, 목록 불완전으로 건너뛴 책 {len(skipped)}권), 응답 오류·형식 이상 {len(errors)}곳",
                f"- 거짓 \"없음\": 공지 기관 아닌 곳 **{len(false_other)}건**, 공지 기관(공급자 재수집, 따로 셈) {len(false_notice)}건"]
        for lst, isbn, x in false_other[:10]:
            out.append(f"  - {lst} {isbn}: {x['name']}({x['libCode']}, {x['km']}km)")
        if upper is not None:
            out.append(f"- 0건이어도 거짓 \"없음\" 비율의 95% 상한은 약 {upper:.1%}({n}곳 기준)")
        out.append(f"- 판정 → **{r3}**")
    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--summarize":
        summarize(sys.argv[2])
    else:
        main()
