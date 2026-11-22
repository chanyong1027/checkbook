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
응답 원문은 남기지 않는다(도서관 코드·이름·건수·시간만). 정보나루는 초당 2회 미만, 한 조회 안의 시도 병렬(최대 3)만 겹친다.
이 호출은 앱의 공급자 예산(ExternalCallBudget)에 잡히지 않는다(같은 키를 쓰므로 정보나루 쪽 하루 사용량에는 들어간다).
"""
import argparse
import concurrent.futures
import subprocess
import json
import math
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://data4library.kr/api"
BIG_PAGE = 1000
GAP_SECONDS = 0.6  # 차례로 부를 때 간격(초당 2회 미만)
LOOKUP_GAP_SECONDS = 1.5  # 지역 조회 단계 사이 간격(세 시도 병렬 단계도 평균 초당 2회 미만이 되게)
ROUNDS = 3
NEAREST_ABSENT = 5
TIMEOUT_SECONDS = 15
# error 칸에 이 말이 있으면 키·한도 오류로 보고 바로 멈춘다. 그 밖의 오류 칸(표본 하나의 잘못된 코드 등)은 연달아
# ERROR_STREAK_LIMIT번 나올 때만 멈춘다 — 한 건 때문에 표본 전체를 잃지 않게
FATAL_ERROR_WORDS = ("인증", "키", "key", "한도", "초과", "limit", "auth", "권한", "quota", "ip 등록")
ERROR_STREAK_LIMIT = 3


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
        # 429·키 오류·한도 초과를 받으면 나머지 호출을 하지 않는다(한도를 더 나쁘게 하지 않게)
        self.fatal = None
        self._error_streak = 0

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
            if e.code == 429:
                self.fatal = self.fatal or "HTTP 429"
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
            msg = str(resp.get("error"))[:80]
            # errCode도 본다: 11-21 "1일 500건 이상 요청 시 IP 등록이 필요합니다"는 errCode outOflimit로 왔다
            code = str(resp.get("errCode") or "")
            with self._lock:
                self._error_streak += 1
                if any(w in (msg + " " + code).lower() for w in FATAL_ERROR_WORDS):
                    self.fatal = self.fatal or f"error 칸(키·한도): {msg}"
                elif self._error_streak >= ERROR_STREAK_LIMIT:
                    self.fatal = self.fatal or f"error 칸 {ERROR_STREAK_LIMIT}회 연속: {msg}"
            return resp, elapsed, f"error 칸: {msg}"
        with self._lock:
            self._error_streak = 0
        return resp, elapsed, None


def lib_codes(resp):
    out = []
    for item in (resp or {}).get("libs", []) or []:
        lib = item.get("lib") if isinstance(item, dict) else None
        if isinstance(lib, dict) and lib.get("libCode"):
            out.append(lib["libCode"])
    return out


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
            if client.fatal:
                pages.append({"pageNo": page, "sec": 0, "error": "멈춤(앞선 한도 오류)", "numFound": None, "resultNum": None, "got": 0})
                break
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


def db_library_coords():
    """운영 DB의 활성 도서관 좌표. (좌표 {코드: (위도, 경도)} 또는 None, 못 읽은 줄 수, 실패 설명 또는 None).
    앱의 주변 탐색이 쓰는 값이다(정보나루 좌표가 비어 와도 PublicLibrary.refresh가 옛 좌표를 지켜 후보에 남는다)"""
    d = "/opt/checkbook/current/deploy"
    cmd = ["docker", "compose", "--project-directory", d, "-f", f"{d}/compose.yaml", "--env-file", "/opt/checkbook/.env",
           "exec", "-T", "postgres", "sh", "-c",
           'psql -U "$POSTGRES_USER" -d checkbook -tA -F "|" -c "SELECT lib_code, lat, lon FROM public_library WHERE active"']
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        return None, 0, type(e).__name__
    if res.returncode != 0:
        return None, 0, f"종료 코드 {res.returncode}: {res.stderr.strip()[:160]}"
    coords = {}
    bad = 0
    for line in res.stdout.splitlines():
        if not line.strip():
            continue
        parts = line.split("|")
        try:
            coords[parts[0]] = (float(parts[1]), float(parts[2]))
        except (IndexError, ValueError):
            bad += 1
    if not coords:
        return None, bad, "활성 도서관이 0곳으로 읽힘"
    return coords, bad, None


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
    notice_cities = plan["noticeInstitutions"].get("cities", [])
    out = open(args.out, "w", encoding="utf-8")
    started = time.time()
    anchors = {"seoul": "seoul-cityhall", "gyeonggi": "ansan-cityhall", "gangwon": "wonju-cityhall"}
    # 판정이 표본 완전성을 확인하게 기대 기록 수를 남긴다(끝 표시와 함께)
    expected = {
        "A": 1 if "A" in args.phases else 0,
        "B": ROUNDS * sum(sum(1 for b in plan["books"][p["books"]] if b["bin"] == "common") for p in plan["positions"].values())
        if "B" in args.phases else 0,
        "C": sum(len(plan["books"][k]) for k in anchors) if "C" in args.phases else 0,
    }
    emit(out, {"phase": "start", "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "limit": args.limit, "phases": args.phases,
               "expected": expected})

    if "A" in args.phases:
        isbn = next(b["isbn"] for b in plan["books"]["gyeonggi"] if b["bin"] == "common")
        one, one_codes = fetch_region(client, isbn, 31, BIG_PAGE)
        if not client.fatal:
            time.sleep(GAP_SECONDS)
            resp, el, err = client.get("libSrchByBook", isbn=isbn, region=31)  # pageSize 없이: 기본 쪽 크기
            default = {"sec": round(el, 3), "error": err, "numFound": as_int((resp or {}).get("numFound")),
                       "pageSize": as_int((resp or {}).get("pageSize")), "resultNum": as_int((resp or {}).get("resultNum"))}
        if not client.fatal:
            time.sleep(GAP_SECONDS)
            split, split_codes = fetch_region(client, isbn, 31, 100)
            emit(out, {"phase": "A", "isbn": isbn, "region": 31, "onePage": one, "defaultPage": default, "split100": split,
                       "sameCodes": one_codes == split_codes})

    if "B" in args.phases and not client.fatal:
        for rnd in range(1, (1 if args.limit else ROUNDS) + 1):
            for key, pos in plan["positions"].items():
                commons = [b for b in plan["books"][pos["books"]] if b["bin"] == "common"][: args.limit or None]
                for b in commons:
                    if client.fatal:
                        break
                    time.sleep(LOOKUP_GAP_SECONDS)
                    stage, results, _ = region_stage(client, b["isbn"], pos["regions"])
                    emit(out, {"phase": "B", "round": rnd, "position": key, "isbn": b["isbn"], "regions": pos["regions"],
                               "stageSec": round(stage, 3), "calls": sum(len(r["pages"]) for r in results), "perRegion": results})

    if "C" in args.phases and not client.fatal:
        # 시도 소속은 정보나루 libSrch로 정하고, 좌표는 운영 DB(앱이 거리 계산에 쓰는 값)를 쓴다. 시도 넷
        libs = {}
        raw = {}
        for region in sorted({r for p in plan["positions"].values() for r in p["regions"]} | set(plan["bookRegions"].values())):
            if client.fatal:
                break
            time.sleep(GAP_SECONDS)
            resp, el, err = client.get("libSrch", region=region, pageNo=1, pageSize=BIG_PAGE)
            if err:
                # 좌표 목록이 빠지면 규칙 3 표본이 줄어 "그대로"로 잘못 나온다 — 판정이 보류하게 기록한다
                emit(out, {"phase": "C", "error": f"libSrch {region}: {err}"})
                continue
            if not resp.get("libs"):
                # 3월 libSrch 오류 삼킴과 같은 꼴(오류 표시 없이 빈 목록) — 보류되게 오류로 남긴다
                emit(out, {"phase": "C", "error": f"libSrch {region}: 빈 목록(numFound {resp.get('numFound')})"})
                continue
            if as_int(resp.get("numFound")) != len(resp.get("libs", [])):
                emit(out, {"phase": "C", "warn": f"libSrch {region}: numFound {resp.get('numFound')} 중 {len(resp.get('libs', []))}곳만 받음"})
            for l in resp.get("libs", []):
                lib = l.get("lib", {}) if isinstance(l, dict) else {}
                if lib.get("libCode"):
                    raw[lib["libCode"]] = (lib.get("libName", ""), lib.get("latitude"), lib.get("longitude"), region,
                                           lib.get("address") or "")
        # 좌표는 운영 DB(앱이 거리 계산에 쓰는 값)를 먼저 쓰고, 측정 PC처럼 DB를 못 읽으면 정보나루 좌표를 쓴다
        db, db_bad, db_err = db_library_coords()
        on_server = Path("/opt/checkbook").exists()
        if db is None and on_server:
            # 서버인데 DB를 못 읽었다 — 정보나루 좌표로 대신하면 화면과 다른 표본이 되므로 보류되게 남긴다
            emit(out, {"phase": "C", "warn": f"운영 DB 좌표를 못 읽음(서버 실행): {db_err}"})
        if db_bad:
            emit(out, {"phase": "C", "warn": f"운영 DB 좌표 줄 {db_bad}개를 못 읽음 — 그 도서관이 표본에서 빠질 수 있다"})
        dropped = []
        for code, (name, la, lo, region, addr) in raw.items():
            if db is not None:
                if code not in db:
                    continue  # 앱의 활성 후보가 아니다(주변 목록에 나오지 않음)
                la, lo = db[code]
            try:
                libs[code] = (name, float(la), float(lo), region, addr)
            except (TypeError, ValueError):
                dropped.append(code)
        emit(out, {"phase": "C", "note": f"좌표 출처: {'운영 DB 활성 도서관 ' + str(len(db)) + '곳' if db is not None else '정보나루 libSrch(운영 DB를 못 읽음' + (' — 서버, 위 경고' if on_server else ' — 측정 PC 실행') + ')'}"
                   + (f", 좌표 없어 뺀 {len(dropped)}곳" if dropped else "")})
        for list_key, pos_key in anchors.items():
            pos = plan["positions"][pos_key]
            nearest = sorted((haversine(pos["lat"], pos["lon"], la, lo), code, name, addr)
                             for code, (name, la, lo, region, addr) in libs.items() if region in pos["regions"])
            if db is not None:
                # DB에는 활성인데 오늘 libSrch 목록(이 스크립트가 시도를 아는 곳)에 없는 가까운 도서관 — 화면 후보인데
                # 표본에서 빠질 수 있는 곳이다. 시도를 몰라도 거리로 센다
                missing = sorted(round(haversine(pos["lat"], pos["lon"], la, lo), 1)
                                 for code, (la, lo) in db.items() if code not in raw
                                 and haversine(pos["lat"], pos["lon"], la, lo) <= 10)
                emit(out, {"phase": "C", "note": f"{pos_key}: DB 활성인데 libSrch 목록에 없는 10km 안 도서관 {len(missing)}곳"
                           + (f"(가까운 거리 {missing[:5]}km)" if missing else "")})
            books = plan["books"][list_key]
            if args.limit:  # 묶음마다 앞에서 N권
                books = [b for bin_ in ("common", "middle", "rare", "absent") for b in [x for x in books if x["bin"] == bin_][: args.limit]]
            for b in books:
                if client.fatal:
                    break
                time.sleep(LOOKUP_GAP_SECONDS)
                _, results, listed = region_stage(client, b["isbn"], pos["regions"])
                complete = all(r["complete"] for r in results)
                checks = []
                if complete:
                    # 목록에 없는 가까운 도서관 5곳 — 목록이 맞다면 모두 "없음"이어야 한다
                    for dist, code, name, addr in [x for x in nearest if x[1] not in listed][:NEAREST_ABSENT]:
                        if client.fatal:
                            break
                        time.sleep(GAP_SECONDS)
                        resp, el, err = client.get("bookExist", libCode=code, isbn13=b["isbn"])
                        result = (resp or {}).get("result")
                        result = result if isinstance(result, dict) else {}
                        has = str(result.get("hasBook", "")).strip().upper() or None
                        checks.append({"libCode": code, "name": name, "km": round(dist, 2), "sec": round(el, 3), "error": err,
                                       "hasBook": has, "falseAbsent": has == "Y",
                                       "notice": any(n in name for n in notice),
                                       # 이름에 시스템 이름이 없는 분관을 놓치지 않으려는 보조 표시(멈춤 판정에는 쓰지 않는다)
                                       "noticeCity": any(c in addr for c in notice_cities)})
                emit(out, {"phase": "C", "list": list_key, "position": pos_key, "isbn": b["isbn"], "bin": b["bin"],
                           "listComplete": complete, "listed": len(listed), "perRegion": results, "checks": checks})
    if client.fatal:
        # 끝 표시를 쓰지 않는다 — 판정은 표본 부족으로 보류한다
        emit(out, {"phase": "aborted", "reason": client.fatal, "calls": client.calls})
        out.close()
        print(f"멈춤: {client.fatal} — 정보나루 {client.calls}회에서 중단 → {args.out}", file=sys.stderr)
        sys.exit(1)
    emit(out, {"phase": "end", "calls": client.calls})
    out.close()
    print(f"끝: 정보나루 {client.calls}회, {time.time() - started:.0f}초 → {args.out}", file=sys.stderr)


# --- 판정(측정 PC에서): python3 scripts/measure/api_check.py --summarize build/measure/api-check/<파일>.jsonl ---
RULE1_MAX_CALLS = 4  # 지역 조회 호출 + 대출 확인 5 ≤ 9.5
RULE2_TARGET_SEC = 1.5  # 첫 표시 2초 − 지역 조회 밖 몫 0.5초 [가정]
RULE2_SHARE = 0.95  # D3: 95% 이상 기준 이하 + 가장 느린 값 기준의 2배 이하


def summarize(path, rule2_target=None):
    """rule2_target: 규칙 2 문턱(초). 없으면 처음 정한 1.5초. 다시 판정할 때는 1층 "전" 측정에서 잰 밖 몫으로
    정한 T = 2.0 − m을 넘긴다(summarize.py가 m과 T를 낸다, 측정 규칙 문서 8절)"""
    target = RULE2_TARGET_SEC if rule2_target is None else rule2_target
    recs = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    start = next((r for r in recs if r.get("phase") == "start"), {})
    expected = start.get("expected") or {}
    got = {k: sum(1 for r in recs if r.get("phase") == k and ("checks" in r if k == "C" else True)) for k in ("A", "B", "C")}
    # 끝 표시가 없거나 기대 기록 수에 못 미치거나 --limit 실행이면 "그대로"를 내지 않는다(이미 성립한 멈춤은 그대로)
    short = [f"{k} {got[k]}/{expected.get(k)}" for k in ("A", "B", "C") if expected.get(k) and got[k] < expected[k]]
    unfinished = not any(r.get("phase") == "end" for r in recs)
    partial = bool(short or unfinished or start.get("limit") or not expected)

    def hold_unless_stop(verdict):
        if verdict.startswith("멈춤") or not partial:
            return verdict
        why = ", ".join(short) or ("끝 표시 없음" if unfinished else "--limit 실행" if start.get("limit") else "기대 수 기록 없음")
        # 이미 다른 이유로 보류면 그 사유를 지우지 않고 표본 부족을 덧붙인다
        return f"{verdict}, 표본 부족: {why}" if verdict.startswith("판정 보류") else f"판정 보류(표본 부족: {why})"
    out = [f"# 순서 4 API 확인 판정 — `{path}`", "", f"- 실행 {start.get('at')}" + (f" ⚠ --limit {start['limit']}(스크립트 확인용, 판정에 쓰지 않음)" if start.get("limit") else "")]
    aborted = next((r for r in recs if r.get("phase") == "aborted"), None)
    if aborted:
        out.append(f"- ⚠ 실행이 멈춤: {aborted.get('reason')} (정보나루 {aborted.get('calls')}회에서)")
    out.append(f"- 기록: A {got['A']}/{expected.get('A')}, B {got['B']}/{expected.get('B')}, C {got['C']}/{expected.get('C')}, 끝 표시 {'있음' if not unfinished else '없음'}"
               + (" → **표본 부족이라 '그대로'는 판정 보류로 둔다**" if partial else ""))
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
        # 판정은 모든 시도 응답이 완전한 표본으로만 한다. 시간 초과·오류 칸이 든 표본은 호출 수·시간이 실제와 달라
        # (시간 초과는 15초까지 늘고, 오류 칸은 빨리 끝난다) 그 표본으로 멈춤도 그대로도 내지 않는다
        ok = [r for r in b if all(x["complete"] for x in r["perRegion"])]
        b_incomplete = len(b) - len(ok)
        known_total = bool(expected.get("B"))
        total = expected.get("B") or len(b)
        out += ["", "## 규칙 1·2 (위치마다 흔한 책, 걸치는 시도를 병렬로)", "", "| 위치 | 시도 | 조회(완전) | 호출 수 | 단계 초(최소~최대) |", "|---|---|---|---|---|"]
        for key in dict.fromkeys(r["position"] for r in b):
            rs = [r for r in ok if r["position"] == key]
            n_all = sum(1 for r in b if r["position"] == key)
            if not rs:
                out.append(f"| {key} | — | 0/{n_all} | — | — |")
                continue
            ss = [r["stageSec"] for r in rs]
            out.append(f"| {key} | {rs[0]['regions']} | {len(rs)}/{n_all} | {min(r['calls'] for r in rs)}~{max(r['calls'] for r in rs)} | {min(ss):.2f}~{max(ss):.2f} |")
        uncertain = partial or b_incomplete
        why = f"불완전한 시도 응답 {b_incomplete}건" if b_incomplete else f"표본 부족: B {len(b)}/{total}"
        if ok:
            calls = [r["calls"] for r in ok]
            secs = sorted(r["stageSec"] for r in ok)
            over = sum(1 for v in secs if v > target)
            # 남은 표본이 모두 좋아도 뒤집히지 않는 실패만 "멈춤"(가장 느린 값이 2배를 넘음, 또는 넘은 표본이 전체의 5% 초과)
            stop1 = max(calls) > RULE1_MAX_CALLS
            # 비율로 확정하려면 전체 기대 수를 알아야 한다(기대 수가 없는 옛 기록이면 최대 시간 기준만 확정)
            stop2 = secs[-1] > target * 2 or (known_total and over > (1 - RULE2_SHARE) * total)
            r1 = "멈춤(이미 pageSize 1000 — 시도 수가 원인인지 적고 묻는다)" if stop1 else (f"판정 보류({why})" if uncertain else "그대로")
            r2 = "멈춤" if stop2 else (f"판정 보류({why})" if uncertain else ("그대로" if over <= (1 - RULE2_SHARE) * len(secs) else "멈춤"))
            out += ["", f"- 규칙 1(호출 ≤ {RULE1_MAX_CALLS}): 완전한 표본 {len(ok)}건 최대 {max(calls)} → **{r1}**",
                    f"- 규칙 2(단계 ≤ {target:.2f}초{'' if rule2_target is None else ', 다시 정한 문턱'}, 95% + 최대 {target * 2:.2f}초): 완전한 표본 {len(ok)}건(전체 {total}건 중) "
                    f"넘은 것 {over}건, 최대 {secs[-1]:.2f}초 → **{r2}**"]
        else:
            out += ["", f"- 규칙 1·2: 완전한 표본이 없음 → **판정 보류({why})**"]
    c = [r for r in recs if r.get("phase") == "C" and "checks" in r]
    if c:
        checks = [x for r in c for x in r["checks"]]
        false_other = [(r["list"], r["isbn"], x) for r in c for x in r["checks"] if x["falseAbsent"] and not x["notice"]]
        false_notice = [x for x in checks if x["falseAbsent"] and x["notice"]]
        errors = [x for x in checks if x["error"] or x["hasBook"] not in ("Y", "N")]
        skipped = [r for r in c if not r["listComplete"]]
        coord_errors = [r.get("error") or r.get("warn") for r in recs if r.get("phase") == "C" and (r.get("error") or r.get("warn"))]
        empty_lists = [lst for lst in dict.fromkeys(r["list"] for r in c)
                       if not any(r["checks"] for r in c if r["list"] == lst)]
        held = errors or skipped or coord_errors or empty_lists
        r3 = "멈춤(원인을 적고 묻는다)" if false_other else ("판정 보류(확인 못 한 칸·불완전 목록·좌표 목록 오류)" if held else "그대로")
        r3 = hold_unless_stop(r3)
        notes = [r["note"] for r in recs if r.get("phase") == "C" and r.get("note")]
        out += ["", "## 규칙 3 (목록에 없는 가까운 도서관이 정말 없는가)"] + ([f"- 참고: {'; '.join(notes)}"] if notes else []) + [
                f"- 확인 {len(checks)}곳(책 {len(c)}권, 목록 불완전으로 건너뛴 책 {len(skipped)}권), 응답 오류·형식 이상 {len(errors)}곳, 좌표 목록 오류·일부만 받음 {len(coord_errors)}건, 확인 0곳인 목록 {empty_lists or '없음'}"]
        for lst in dict.fromkeys(r["list"] for r in c):
            mine = [x for r in c if r["list"] == lst for x in r["checks"]]
            out.append(f"  - {lst}: 확인 {len(mine)}곳(목표 90), 서로 다른 도서관 {len({x['libCode'] for x in mine})}곳, 공지 기관 {sum(1 for x in mine if x['notice'])}곳")
        out.append(f"- 거짓 \"없음\": 공지 기관 아닌 곳 **{len(false_other)}건**, 공지 기관(공급자 재수집, 따로 셈) {len(false_notice)}건")
        for lst, isbn, x in false_other[:10]:
            city = " — 주소가 공지 도시(이름으로는 공지 기관 아님, 멈춘 뒤 원인 확인에 참고)" if x.get("noticeCity") else ""
            out.append(f"  - {lst} {isbn}: {x['name']}({x['libCode']}, {x['km']}km){city}")
        judged = [x for x in checks if not x["notice"] and x["hasBook"] in ("Y", "N") and not x["error"]]
        if judged:
            n = len(judged)
            out.append(f"- 참고: 공지 기관을 뺀 확인 {n}곳(서로 다른 도서관 {len({x['libCode'] for x in judged})}곳). 같은 도서관이 책마다 되풀이돼 "
                       f"독립 시행이 아니므로, 이항 95% 상한({1 - 0.05 ** (1 / n):.1%})은 실제보다 강하게 말하는 값이다")
        out.append(f"- 판정 → **{r3}**")
    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) in (3, 5) and sys.argv[1] == "--summarize":
        target = float(sys.argv[4]) if len(sys.argv) == 5 and sys.argv[3] == "--rule2-target" else None
        if len(sys.argv) == 5 and target is None:
            raise SystemExit("사용법: --summarize FILE [--rule2-target 초]")
        if target is not None and not (0 < target < 2.0):  # nan도 여기서 걸린다(비교가 모두 거짓)
            raise SystemExit(f"--rule2-target은 0보다 크고 2초보다 작아야 한다: {sys.argv[4]}")
        summarize(sys.argv[2], target)
    else:
        main()
