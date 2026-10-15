#!/usr/bin/env python3
"""알라딘 기준 데이터를 수집한다 (작업 지시서 T0-1). 알라딘 OpenAPI는 2026-10-30에 종료된다.

2026-10-13 당시 앱(AladinClient, 지금은 삭제됨)과 똑같은 요청을 보낸다.
  1. ISBN 조회   — isbns.csv의 전체 ISBN에 ItemLookUp (ItemIdType=ISBN13)
  2. 키워드 식별 — kakao-seed-keywords.csv의 검색어에 ItemSearch (MaxResults=1, SearchTarget=Book). 보조 경로
  3. 중고가·매장 — 카카오 묶음에서 찾은 책 앞쪽 N권에 ItemLookUp+OptResult=usedList, ItemOffStoreList
                   (크롤러를 켤 경우의 정답지)

출력 (전부 로컬 전용, 커밋 금지 — 알라딘 약관상 데이터 저장 제한):
  notes/incidents/aladin-sunset/raw/aladin-<날짜>.jsonl   요청별 원문 응답 + 상태 + 소요 시간
  notes/incidents/aladin-sunset/baseline-summary.md      묶음별 찾은 비율, 지연 분포, 특이 사례

키는 저장소 루트 .env의 ALADIN_TTB_KEY. 표준 라이브러리만 쓴다.
"""

import argparse
import csv
import http.client
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

from collect_kakao_seed_isbns import HERE, RAW_DIR, REPO_ROOT, load_env_key

BASE = "https://www.aladin.co.kr/ttb/api"
SUMMARY_PATH = RAW_DIR.parent / "baseline-summary.md"


def call(path: str, params: dict, timeout: float):
    """반환: (http_status, body_json_or_None, body_text_if_unparsed, elapsed_ms, error)"""
    url = f"{BASE}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status, text = resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, None, e.read().decode("utf-8", errors="replace")[:500], ms(started), f"HTTP {e.code}"
    except (OSError, http.client.HTTPException) as e:
        # URLError·타임아웃·연결 끊김·불완전 응답. 메시지에 URL(키 포함)은 들어가지 않는다
        return None, None, None, ms(started), f"{type(e).__name__}: {e}"
    elapsed = ms(started)
    try:
        return status, json.loads(text.rstrip().rstrip(";")), None, elapsed, None
    except json.JSONDecodeError:
        return status, None, text[:500], elapsed, "응답 JSON 파싱 실패"


def ms(started):
    return round((time.monotonic() - started) * 1000)


def first_item(body):
    items = (body or {}).get("item") or []
    return items[0] if items else None


def summarize_item(item):
    if not item:
        return None
    keys = ("isbn13", "isbn", "itemId", "title", "author", "publisher", "pubDate",
            "priceSales", "priceStandard", "mallType", "categoryName", "cover")
    return {k: item.get(k) for k in keys}


NOT_FOUND_CODES = {8}  # 알라딘 errorCode 8 = "키에 해당하는 상품이 존재하지 않습니다." (오류가 아니라 '없음')


def outcome(e):
    """found / not_found / error"""
    if e.get("item"):
        return "found"
    if e.get("error_code") in NOT_FOUND_CODES or (not e.get("error") and not e.get("error_code")):
        return "not_found"
    return "error"


def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    idx = min(len(values) - 1, max(0, math.ceil(p / 100 * len(values)) - 1))  # nearest-rank
    return values[idx]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--isbns", type=Path, default=HERE / "isbns.csv")
    parser.add_argument("--keywords", type=Path, default=HERE / "kakao-seed-keywords.csv")
    parser.add_argument("--used-sample", type=int, default=20, help="중고가·매장 스냅샷 권수")
    parser.add_argument("--sleep", type=float, default=1.0, help="호출 간격(초)")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--from-raw", type=Path, help="API를 부르지 않고 저장된 원문 jsonl로 요약만 다시 만든다")
    args = parser.parse_args()
    if args.from_raw:
        rebuild_summary(args.from_raw.resolve(), args)
        return

    with args.isbns.open(encoding="utf-8", newline="") as f:
        isbn_rows = list(csv.DictReader(f))
    with args.keywords.open(encoding="utf-8", newline="") as f:
        keyword_rows = list(csv.DictReader(f))
    planned = len(isbn_rows) + len(keyword_rows) + args.used_sample * 2
    print(f"ISBN {len(isbn_rows)}개, 키워드 {len(keyword_rows)}개, 중고·매장 {args.used_sample}권 → 호출 약 {planned}회, "
          f"예상 {planned * (args.sleep + 0.2) / 60:.1f}분")
    if args.dry_run:
        print("dry-run: 입력 형식 정상, API 호출 없음")
        return

    ttb_key = load_env_key("ALADIN_TTB_KEY")
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now()
    raw_path = RAW_DIR / f"aladin-{stamp:%Y%m%d-%H%M%S}.jsonl"
    results = {"isbn": [], "keyword": [], "used": [], "offstore": []}
    first_call = True

    def pace():
        nonlocal first_call
        if not first_call:
            time.sleep(args.sleep)
        first_call = False

    with raw_path.open("w", encoding="utf-8") as raw:
        def record(kind, ref, status, body, text, elapsed, error, extra=None):
            entry = {"kind": kind, **ref, "http_status": status, "elapsed_ms": elapsed, "error": error,
                     "error_code": (body or {}).get("errorCode"), "error_message": (body or {}).get("errorMessage"),
                     "body": body if body is not None else text}
            if extra:
                entry.update(extra)
            raw.write(json.dumps(entry, ensure_ascii=False) + "\n")
            return entry

        # 1. ISBN 조회 (앱의 lookupBook과 같은 요청)
        for row in isbn_rows:
            pace()
            status, body, text, elapsed, error = call("ItemLookUp.aspx", {
                "ttbkey": ttb_key, "ItemId": row["isbn13"], "ItemIdType": "ISBN13",
                "output": "js", "Version": "20131101"}, args.timeout)
            item = summarize_item(first_item(body))
            entry = record("isbn", {"id": row["id"], "isbn13": row["isbn13"], "group": row["group"]},
                           status, body, text, elapsed, error, {"item": item})
            results["isbn"].append(entry)
            if error and status in (401, 403):
                sys.exit("인증 실패: ALADIN_TTB_KEY를 확인하세요")
            # 알라딘은 키 오류를 HTTP 200 + errorCode로 돌려줄 수 있다. 처음 3건이 모두 오류면 키·서비스 문제로 보고 중단
            first3 = results["isbn"][:3]
            if len(first3) == 3 and all(outcome(e) == "error" for e in first3):
                sys.exit(f"처음 3건이 모두 오류입니다: {first3[0]['error_code']} {first3[0]['error_message'] or first3[0]['error']}")
            kind = outcome(entry)
            mark = {"found": "찾음", "not_found": "없음"}.get(kind) or f"오류: {error or entry['error_message']}"
            print(f"[{row['id']}] {row['isbn13']} {row['group']:6} {mark} ({elapsed}ms)")

        # 2. 키워드 식별 (앱의 searchBook과 같은 요청, 보조 경로)
        for row in keyword_rows:
            pace()
            status, body, text, elapsed, error = call("ItemSearch.aspx", {
                "ttbkey": ttb_key, "Query": row["keyword"], "QueryType": "Keyword", "MaxResults": 1,
                "SearchTarget": "Book", "output": "js", "Version": "20131101"}, args.timeout)
            item = summarize_item(first_item(body))
            results["keyword"].append(record("keyword", {"id": row["id"], "keyword": row["keyword"]},
                                             status, body, text, elapsed, error, {"item": item}))
            print(f"[{row['id']}] '{row['keyword']}' → {item['title'][:30] if item else '결과 없음'} ({elapsed}ms)")

        # 3. 중고가·매장 스냅샷 (카카오 묶음에서 알라딘이 찾은 책 앞쪽 N권)
        found_kakao = [e for e in results["isbn"] if e["group"] == "kakao" and e["item"]][:args.used_sample]
        for e in found_kakao:
            ref = {"id": e["id"], "isbn13": e["isbn13"]}
            pace()
            status, body, text, elapsed, error = call("ItemLookUp.aspx", {
                "ttbkey": ttb_key, "ItemId": e["isbn13"], "ItemIdType": "ISBN13", "OptResult": "usedList",
                "output": "js", "Version": "20131101"}, args.timeout)
            results["used"].append(record("used", ref, status, body, text, elapsed, error))
            pace()
            status, body, text, elapsed, error = call("ItemOffStoreList.aspx", {
                "ttbkey": ttb_key, "ItemId": e["isbn13"], "ItemIdType": "ISBN13", "output": "js"}, args.timeout)
            results["offstore"].append(record("offstore", ref, status, body, text, elapsed, error))
            print(f"[{e['id']}] 중고·매장 스냅샷 저장")

    write_summary(results, raw_path, stamp, args)
    print()
    print(f"원문 → {raw_path.relative_to(REPO_ROOT)} (커밋 금지)")
    print(f"요약 → {SUMMARY_PATH.relative_to(REPO_ROOT)}")


def rebuild_summary(raw_path, args):
    if not raw_path.name.startswith("aladin-") or REPO_ROOT not in raw_path.parents:
        sys.exit(f"저장소 안의 aladin-<시각>.jsonl 파일만 받습니다: {raw_path}")
    results = {"isbn": [], "keyword": [], "used": [], "offstore": []}
    for line in raw_path.read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        if e.get("kind") in results:
            results[e["kind"]].append(e)
    stamp = datetime.strptime(raw_path.stem.split("aladin-")[1], "%Y%m%d-%H%M%S")
    write_summary(results, raw_path, stamp, args, rebuilt=True)
    print(f"요약 재생성 → {SUMMARY_PATH.relative_to(REPO_ROOT)}")


def write_summary(results, raw_path, stamp, args, rebuilt=False):
    isbn = results["isbn"]
    lines = [
        "# 알라딘 기준 데이터 요약 (T0-1)",
        "",
        f"- 수집 시각: {stamp:%Y-%m-%d %H:%M:%S} (로컬 WSL에서 호출, 지연은 EC2 기준이 아님)",
        f"- 원문: `{raw_path.relative_to(REPO_ROOT)}` (커밋 금지)",
        f"- 요청 형식: 2026-10-13 당시 앱 `AladinClient`(지금은 삭제됨)와 동일 (ItemLookUp ItemIdType=ISBN13 / ItemSearch MaxResults=1 SearchTarget=Book)",
        (f"- 원문에서 요약만 재생성함 (원 실행의 호출 간격·타임아웃은 원문에 기록되지 않음)" if rebuilt
         else f"- 호출 간격 {args.sleep}s, 타임아웃 {args.timeout}s"),
        "",
        "## 1. ISBN 조회 — 묶음별 결과",
        "",
        "| 묶음 | 요청 | 찾음 | 결과 없음(errorCode 8 포함) | 오류 | 요청 ISBN과 응답 isbn13이 다름 |",
        "|---|---|---|---|---|---|",
    ]
    for group in ("kakao", "millie", "loan", "tricky", "전체"):
        rows = isbn if group == "전체" else [e for e in isbn if e["group"] == group]
        found = [e for e in rows if outcome(e) == "found"]
        not_found = [e for e in rows if outcome(e) == "not_found"]
        errors = [e for e in rows if outcome(e) == "error"]
        mismatch = [e for e in found if (e["item"].get("isbn13") or "") != e["isbn13"]]
        lines.append(f"| {group} | {len(rows)} | {len(found)} | {len(not_found)} | "
                     f"{len(errors)} | {len(mismatch)} |")

    lat = [e["elapsed_ms"] for e in isbn if e["http_status"]]
    if lat:
        lines += ["", f"ISBN 조회 지연(ms, 응답을 받은 요청 n={len(lat)}, 알라딘 오류 코드 응답 포함): p50 {statistics.median(lat):.0f}, "
                      f"p95 {pct(lat, 95)}, max {max(lat)}"]

    lines += ["", "## 2. 특이 사례", "", "### 찾지 못했거나 오류가 난 ISBN", "",
              "| id | ISBN | 묶음 | HTTP | 오류 코드 | 메시지 |", "|---|---|---|---|---|---|"]
    for e in isbn:
        if not e["item"]:
            lines.append(f"| {e['id']} | {e['isbn13']} | {e['group']} | {e['http_status']} | "
                         f"{e['error_code'] or ''} | {(e['error_message'] or e['error'] or '')[:60]} |")

    lines += ["", "### 까다로운 경우(tricky) 전체", "",
              "| id | 요청 코드 | 응답 isbn13 | 제목 | mallType |", "|---|---|---|---|---|"]
    for e in isbn:
        if e["group"] == "tricky":
            it = e["item"] or {}
            lines.append(f"| {e['id']} | {e['isbn13']} | {it.get('isbn13', '')} | {(it.get('title') or '(없음)')[:40]} | "
                         f"{it.get('mallType', '')} |")

    lines += ["", "## 3. 키워드 식별 (보조)", "",
              "| id | 검색어 | 첫 결과 | 저자 | 출판사 | isbn13 |", "|---|---|---|---|---|---|"]
    for e in results["keyword"]:
        it = e["item"] or {}
        lines.append(f"| {e['id']} | {e['keyword']} | {(it.get('title') or '(결과 없음)')[:36]} | "
                     f"{(it.get('author') or '')[:24]} | {it.get('publisher', '')} | {it.get('isbn13', '')} |")

    used_ok = sum(1 for e in results["used"] if not e["error"])
    off_ok = sum(1 for e in results["offstore"] if not e["error"])
    lines += ["", "## 4. 중고가·매장 스냅샷", "",
              f"- 중고 목록(usedList) {used_ok}/{len(results['used'])}건, 매장(ItemOffStoreList) {off_ok}/{len(results['offstore'])}건 저장",
              "- 크롤러를 켤 경우 파싱 결과를 대조할 정답지로 쓴다", ""]
    SUMMARY_PATH.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
