#!/usr/bin/env python3
"""YES24 데이터를 알라딘 기준 데이터와 같은 입력으로 수집한다 (작업 지시서 T0-2).

  0. 탐침        — 응답 헤더의 실제 호출 한도(X-RateLimit-*), 없는 ISBN의 오류 코드,
                   추천 목록 엔드포인트(bestseller, newproductAttention) 동작 확인
  1. ISBN 조회   — isbns.csv 전체에 GET /v1/goods/itemDetail (searchType=ISBN13, detail=N)
  2. 키워드 식별 — kakao-seed-keywords.csv에 GET /v1/goods/itemList (category=BOOK, sort=RELATION, pageSize=1). 보조

출력 (로컬 전용, 커밋 금지 — YES24 약관상 데이터 축적 제한):
  notes/incidents/aladin-sunset/raw/yes24-<날짜>.jsonl
  notes/incidents/aladin-sunset/yes24-summary.md

키는 .env의 YES24_KEY (또는 YES24_API_KEY). 요청 명세는 yes24-AX/yes24-openapi-samples의 OpenAPI v1 기준.
"""

import argparse
import csv
import http.client
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

from collect_kakao_seed_isbns import HERE, RAW_DIR, REPO_ROOT, load_env_key
from collect_aladin import pct

BASE = "https://apis.yes24.com"
SUMMARY_PATH = RAW_DIR.parent / "yes24-summary.md"
NOT_FOUND_ISBN = "9799999999990"  # 존재하지 않을 ISBN13 (오류 코드 확인용)
ITEM_FIELDS = ("itemId", "title", "subTitle", "author", "publisher", "isbn10", "isbn13", "shopPrice",
               "salePrice", "publishDate", "itemStatus", "goodsType", "goodsSortNm", "link", "cover")


def api_key():
    env_file = REPO_ROOT / ".env"
    if not env_file.exists():
        sys.exit(f".env 파일이 없습니다: {env_file}")
    names = {l.split("=", 1)[0].strip() for l in env_file.read_text(encoding="utf-8").splitlines() if "=" in l}
    return load_env_key("YES24_KEY" if "YES24_KEY" in names else "YES24_API_KEY")


NOT_FOUND_CODES = {"GOODS_001", "GOODS_002"}  # 상품 없음 / ISBN13 미매칭 (오류가 아니라 '없음')


def outcome(e):
    """found / not_found / error"""
    if e.get("item"):
        return "found"
    if e.get("error_code") in NOT_FOUND_CODES:
        return "not_found"
    # 200이어도 JSON이 아니거나(점검 페이지 등) success=false면 오류로 본다
    if e.get("status") == 200 and isinstance(e.get("body"), dict) and e["body"].get("success") is not False and not e.get("error_code"):
        return "not_found"
    return "error"


def call(key, path, params, timeout):
    """반환: dict(status, headers(rate-limit 관련만), body, text, elapsed_ms, error)"""
    url = f"{BASE}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"X-Api-Key": key, "Accept": "application/json"})
    started = time.monotonic()
    status, headers, text, error = None, {}, None, None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status, headers, text = resp.status, dict(resp.headers), resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        status, headers, error = e.code, dict(e.headers), f"HTTP {e.code}"
        text = e.read().decode("utf-8", errors="replace")
    except (OSError, http.client.HTTPException) as e:
        # URLError·타임아웃·연결 끊김·불완전 응답 (키는 헤더라 메시지에 들어가지 않음)
        error = f"{type(e).__name__}: {e}"
    elapsed = round((time.monotonic() - started) * 1000)
    body = None
    if text:
        try:
            body = json.loads(text)
        except json.JSONDecodeError:
            pass
    rate = {k: v for k, v in headers.items() if k.lower().startswith(("x-ratelimit", "retry-after"))}
    return {"status": status, "rate_headers": rate, "body": body,
            "text": None if body is not None else (text or "")[:500], "elapsed_ms": elapsed, "error": error}


def items_of(body):
    data = (body or {}).get("data")
    if isinstance(data, dict):
        if isinstance(data.get("items"), list):
            return data["items"]
        if data.get("isbn13") or data.get("itemId"):
            return [data]
    if isinstance(data, list):
        return data
    return []


def slim(item):
    return {k: item.get(k) for k in ITEM_FIELDS} if item else None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--isbns", type=Path, default=HERE / "isbns.csv")
    parser.add_argument("--keywords", type=Path, default=HERE / "kakao-seed-keywords.csv")
    parser.add_argument("--sleep", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--probe-only", action="store_true", help="탐침(0단계)만 실행")
    parser.add_argument("--from-raw", type=Path, help="API를 부르지 않고 저장된 원문 jsonl로 요약만 다시 만든다")
    args = parser.parse_args()
    if args.from_raw:
        args.from_raw = args.from_raw.resolve()
        if not args.from_raw.name.startswith("yes24-") or REPO_ROOT not in args.from_raw.parents:
            sys.exit(f"저장소 안의 yes24-<시각>.jsonl 파일만 받습니다: {args.from_raw}")
        res = {"probe": [], "isbn": [], "keyword": []}
        for line in args.from_raw.read_text(encoding="utf-8").splitlines():
            e = json.loads(line)
            if e.get("kind") in res:
                res[e["kind"]].append(e)
        write_summary(res, args.from_raw, datetime.strptime(args.from_raw.stem.split("yes24-")[1], "%Y%m%d-%H%M%S"))
        print(f"요약 재생성 → {SUMMARY_PATH.relative_to(REPO_ROOT)}")
        return

    with args.isbns.open(encoding="utf-8", newline="") as f:
        isbn_rows = list(csv.DictReader(f))
    with args.keywords.open(encoding="utf-8", newline="") as f:
        keyword_rows = list(csv.DictReader(f))

    key = api_key()
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now()
    raw_path = RAW_DIR / f"yes24-{stamp:%Y%m%d-%H%M%S}.jsonl"
    res = {"probe": [], "isbn": [], "keyword": []}
    first = True

    def pace():
        nonlocal first
        if not first:
            time.sleep(args.sleep)
        first = False

    with raw_path.open("w", encoding="utf-8") as raw:
        def do(kind, ref, path, params):
            pace()
            r = call(key, path, params, args.timeout)
            items = items_of(r["body"])
            entry = {"kind": kind, **ref, "path": path, "params": params, **r,
                     "error_code": (r["body"] or {}).get("errorCode"),
                     "message": (r["body"] or {}).get("message"),
                     "item": slim(items[0]) if items else None, "item_count": len(items)}
            raw.write(json.dumps(entry, ensure_ascii=False) + "\n")
            if r["status"] in (401, 403):
                sys.exit(f"인증 실패({r['status']}): YES24 키를 확인하세요. 메시지: {entry['message'] or r['text']}")
            if r["status"] == 429:
                sys.exit(f"호출 한도 초과(429). Retry-After: {r['rate_headers']}")
            return entry

        # 0. 탐침
        probes = [
            ("detail_found", "/v1/goods/itemDetail", {"searchType": "ISBN13", "query": "9788936434120", "detail": "N"}),
            ("detail_not_found", "/v1/goods/itemDetail", {"searchType": "ISBN13", "query": NOT_FOUND_ISBN, "detail": "N"}),
            ("detail_non_isbn_code", "/v1/goods/itemDetail", {"searchType": "ISBN13", "query": "2004566000649", "detail": "N"}),
            ("list_keyword", "/v1/goods/itemList", {"query": "인간 실격", "category": "BOOK", "sort": "RELATION", "pageSize": 3, "detail": "N"}),
            ("bestseller_no_category", "/v1/category/bestseller", {"pageSize": 5}),
            ("new_attention_no_category", "/v1/category/newproductAttention", {"pageSize": 5}),
        ]
        for name, path, params in probes:
            e = do("probe", {"probe": name}, path, params)
            res["probe"].append(e)
            print(f"[probe] {name}: HTTP {e['status']} code={e['error_code']} items={e['item_count']} "
                  f"({e['elapsed_ms']}ms) rate={e['rate_headers']}")
        if args.probe_only:
            write_summary(res, raw_path, stamp)
            return
        found_probe = next(e for e in res["probe"] if e["probe"] == "detail_found")
        if found_probe["item_count"] < 1:
            # 응답 구조를 잘못 읽고 있으면 전체 실행이 '전부 없음'으로 끝난다. 본 수집 전에 멈춘다
            write_summary(res, raw_path, stamp)
            sys.exit("탐침 detail_found가 상품을 읽지 못했습니다. 응답 구조(items_of)를 확인하세요")

        # 1. ISBN 조회
        for row in isbn_rows:
            e = do("isbn", {"id": row["id"], "isbn13": row["isbn13"], "group": row["group"]}, "/v1/goods/itemDetail",
                   {"searchType": "ISBN13", "query": row["isbn13"], "detail": "N"})
            res["isbn"].append(e)
            first3 = res["isbn"][:3]
            if len(first3) == 3 and all(outcome(x) == "error" for x in first3):
                write_summary(res, raw_path, stamp)
                sys.exit(f"처음 3건이 모두 오류입니다: HTTP {first3[0]['status']} {first3[0]['error_code']} {first3[0]['message']}")
            mark = "찾음" if e["item"] else f"없음/오류 HTTP {e['status']} {e['error_code'] or ''}"
            print(f"[{row['id']}] {row['isbn13']} {row['group']:6} {mark} ({e['elapsed_ms']}ms)")

        # 2. 키워드 식별 (보조)
        for row in keyword_rows:
            e = do("keyword", {"id": row["id"], "keyword": row["keyword"]}, "/v1/goods/itemList",
                   {"query": row["keyword"], "category": "BOOK", "sort": "RELATION", "pageSize": 1, "detail": "N"})
            res["keyword"].append(e)
            print(f"[{row['id']}] '{row['keyword']}' → {(e['item'] or {}).get('title', '결과 없음')} ({e['elapsed_ms']}ms)")

    write_summary(res, raw_path, stamp)
    print(f"\n원문 → {raw_path.relative_to(REPO_ROOT)} (커밋 금지)\n요약 → {SUMMARY_PATH.relative_to(REPO_ROOT)}")


def write_summary(res, raw_path, stamp):
    L = ["# YES24 수집 요약 (T0-2)", "",
         f"- 수집 시각: {stamp:%Y-%m-%d %H:%M:%S} (로컬 WSL, 지연은 EC2 기준 아님)",
         f"- 원문: `{raw_path.relative_to(REPO_ROOT)}` (커밋 금지)", "",
         "## 0. 탐침", "", "| 항목 | HTTP | 오류 코드 | 메시지 | 결과 수 | 첫 결과 | 지연(ms) |", "|---|---|---|---|---|---|---|"]
    for e in res["probe"]:
        L.append(f"| {e['probe']} | {e['status']} | {e['error_code'] or ''} | {(e['message'] or e['error'] or '')[:40]} | "
                 f"{e['item_count']} | {((e['item'] or {}).get('title') or '')[:30]} | {e['elapsed_ms']} |")
    rate = next((e["rate_headers"] for e in res["probe"] if e["rate_headers"]), {})
    L += ["", "호출 한도 관련 응답 헤더(첫 응답 기준):", "", "```", json.dumps(rate, ensure_ascii=False, indent=2), "```"]

    if res["isbn"]:
        L += ["", "## 1. ISBN 조회 — 묶음별 결과", "", "| 묶음 | 요청 | 찾음 | 없음(GOODS_00x) | 오류 | 요청과 응답 isbn13 다름 | 판매가 있음 | 링크 있음 |",
              "|---|---|---|---|---|---|---|---|"]
        for g in ("kakao", "millie", "loan", "tricky", "전체"):
            rows = res["isbn"] if g == "전체" else [e for e in res["isbn"] if e["group"] == g]
            found = [e for e in rows if outcome(e) == "found"]
            not_found = [e for e in rows if outcome(e) == "not_found"]
            errors = [e for e in rows if outcome(e) == "error"]
            L.append(f"| {g} | {len(rows)} | {len(found)} | {len(not_found)} | {len(errors)} | "
                     f"{sum(1 for e in found if e['item'].get('isbn13') != e['isbn13'])} | "
                     f"{sum(1 for e in found if e['item'].get('salePrice'))} | {sum(1 for e in found if e['item'].get('link'))} |")
        lat = [e["elapsed_ms"] for e in res["isbn"] if e["status"]]
        if lat:
            L += ["", f"ISBN 조회 지연(ms, n={len(lat)}): p50 {statistics.median(lat):.0f}, p95 {pct(lat, 95)}, max {max(lat)}"]
        L += ["", "### 못 찾은 ISBN", "", "| id | ISBN | 묶음 | HTTP | 오류 코드 | 메시지 |", "|---|---|---|---|---|---|"]
        for e in res["isbn"]:
            if not e["item"]:
                L.append(f"| {e['id']} | {e['isbn13']} | {e['group']} | {e['status']} | {e['error_code'] or ''} | "
                         f"{(e['message'] or e['error'] or '')[:50]} |")
    if res["keyword"]:
        L += ["", "## 2. 키워드 식별 (보조)", "", "| id | 검색어 | 첫 결과 | 부제 | 저자 | 출판사 | isbn13 |", "|---|---|---|---|---|---|---|"]
        for e in res["keyword"]:
            it = e["item"] or {}
            L.append(f"| {e['id']} | {e['keyword']} | {(it.get('title') or '(결과 없음)')[:30]} | {(it.get('subTitle') or '')[:20]} | "
                     f"{(it.get('author') or '')[:24]} | {it.get('publisher') or ''} | {it.get('isbn13') or ''} |")
    SUMMARY_PATH.write_text("\n".join(L) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
