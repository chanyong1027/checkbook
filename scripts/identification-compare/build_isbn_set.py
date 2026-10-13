#!/usr/bin/env python3
"""비교용 최종 ISBN 셋(isbns.csv)을 만든다 (작업 지시서 T0-1).

네 묶음을 합친다.
  kakao  — isbns-from-kakao.csv (collect_kakao_seed_isbns.py 결과)
  millie — 5월 밀리 PoC 결정 문서에 이름이 남은 책. 카카오로 1권씩 찾아 ISBN 확보
           (PoC 30권 전체 목록은 남아 있지 않다. 밀리 매칭 비교는 나중에 전체 ISBN으로 한다)
  loan   — 정보나루 인기 대출 목록(loanItemSrch). 메인 추천 '인기 대출' 섹션과 같은 출처
  tricky — tricky-isbns.csv (판매 상태 없음, 비표준 코드, 세트, 파생서, 구판)

출력:  isbns.csv — id, isbn13, group, origin, note (커밋 가능)
       notes/incidents/aladin-sunset/raw/build-isbn-set-<날짜>.jsonl — 원문 (로컬 전용, 커밋 금지)
"""

import argparse
import csv
import http.client
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

from collect_kakao_seed_isbns import (  # 같은 폴더의 스크립트
    HERE, RAW_DIR, REPO_ROOT, kakao_search, load_env_key, split_isbn,
)

DATANARU_URL = "https://data4library.kr/api/loanItemSrch"

# 결정 문서(2026-05-10 밀리 PoC)에 이름이 남아 있고 카카오 셋에 아직 없는 책.
# 괄호 안은 PoC 기록: D=밀리에 라이선스 없음 판정, E=밀리 검색 0건, A·B·C=매칭 실패 원인 분류
MILLIE_QUERIES = [
    ("M01", "파친코 이민진", "PoC D"),
    ("M02", "모순 양귀자", "PoC D"),
    ("M03", "듄 프랭크 허버트", "PoC D"),
    ("M04", "미드나잇 라이브러리 매트 헤이그", "PoC E"),
    ("M05", "첫 여름, 완주 김금희", "PoC C(표본 저자 오류)"),
    ("M06", "브라운스톤", "PoC B(저자 표기 우석(브라운스톤))"),
]


def collect_millie(api_key, raw, sleep):
    rows = []
    for i, (mid, query, note) in enumerate(MILLIE_QUERIES):
        if i > 0:
            time.sleep(sleep)
        try:
            body, elapsed = kakao_search(api_key, query, timeout=5)
        except (OSError, http.client.HTTPException, ValueError) as e:
            print(f"[{mid}] 카카오 호출 실패 — {query}: {type(e).__name__}: {e}")
            continue
        docs = body.get("documents") or []
        raw.write(json.dumps({"group": "millie", "id": mid, "query": query, "documents": docs[:3]},
                             ensure_ascii=False) + "\n")
        for rank, doc in enumerate(docs, start=1):
            isbn13, _, flag = split_isbn(doc.get("isbn"))
            if flag != "none":
                rows.append({"isbn13": isbn13, "group": "millie", "origin": f"kakao:{mid}#{rank}",
                             "note": f"{note} / 질의 '{query}'"})
                print(f"[{mid}] {query} → {doc.get('title', '')[:30]} ({isbn13})")
                break
        else:
            print(f"[{mid}] {query} → ISBN 있는 결과 없음")
    return rows


def collect_loan(auth_key, raw, page_size, timeout):
    params = urllib.parse.urlencode(
        {"authKey": auth_key, "pageNo": 1, "pageSize": page_size, "format": "json"})
    url = f"{DATANARU_URL}?{params}"
    body = None
    # 정보나루 loanItemSrch는 콜드 캐시에서 40초 이상 걸릴 수 있고, 1차 호출이 캐시를 데운다 (FeaturedBooksRefresher와 같은 대응)
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(url, timeout=timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            break
        except (OSError, http.client.HTTPException, ValueError) as e:
            print(f"[loan] {attempt}차 호출 실패: {type(e).__name__}: {e}")
    if body is None:
        print("[loan] 인기 대출 목록을 받지 못했습니다. 이 묶음은 비워 둡니다")
        return []
    api_error = (body.get("response") or {}).get("error")
    if api_error:
        # 정보나루는 키·쿼터 오류를 HTTP 200 + response.error로 준다
        print(f"[loan] 정보나루 오류 응답: {api_error}")
    docs = [d.get("doc", {}) for d in ((body.get("response") or {}).get("docs") or [])]
    raw.write(json.dumps({"group": "loan", "docs": docs}, ensure_ascii=False) + "\n")
    rows = []
    for d in docs:
        isbn13 = (d.get("isbn13") or "").strip()
        if len(isbn13) == 13 and isbn13.isdigit():
            rows.append({"isbn13": isbn13, "group": "loan", "origin": f"datanaru:rank{d.get('ranking', '').strip()}",
                         "note": "정보나루 인기 대출"})
    print(f"[loan] 인기 대출 {len(docs)}건 중 ISBN13 {len(rows)}개")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--loan-size", type=int, default=15)
    parser.add_argument("--loan-timeout", type=float, default=90.0)
    parser.add_argument("--sleep", type=float, default=1.0)
    parser.add_argument("--out", type=Path, default=HERE / "isbns.csv")
    parser.add_argument("--force", action="store_true", help="기존 출력 파일 덮어쓰기 허용")
    args = parser.parse_args()
    if args.out.exists() and not args.force:
        # 인기 대출 목록은 날마다 바뀐다. 알라딘 수집(10/30 이후 재수집 불가)에 쓴 셋을 실수로 바꾸지 않게 막는다
        sys.exit(f"{args.out.name}이 이미 있습니다. 비교 기준 셋이 바뀌지 않도록 덮어쓰지 않습니다(필요하면 --force)")

    kakao_file = HERE / "isbns-from-kakao.csv"
    if not kakao_file.exists():
        sys.exit("isbns-from-kakao.csv가 없습니다. collect_kakao_seed_isbns.py를 먼저 실행하세요")
    with kakao_file.open(encoding="utf-8", newline="") as f:
        kakao_rows = [{"isbn13": r["isbn13"], "group": "kakao", "origin": r["origin"],
                       "note": f"{r['group']} / 검색어 '{r['keyword']}'"} for r in csv.DictReader(f)]
    with (HERE / "tricky-isbns.csv").open(encoding="utf-8", newline="") as f:
        tricky_rows = [{"isbn13": r["isbn13"], "group": "tricky", "origin": f"manual:{r['id']}",
                        "note": f"{r['kind']} / {r['note']}"} for r in csv.DictReader(f)]

    kakao_key = load_env_key("KAKAO_BOOK_REST_API_KEY")
    datanaru_key = load_env_key("DATANARU_AUTH_KEY")
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    raw_path = RAW_DIR / f"build-isbn-set-{datetime.now():%Y%m%d-%H%M%S}.jsonl"

    with raw_path.open("w", encoding="utf-8") as raw:
        millie_rows = collect_millie(kakao_key, raw, args.sleep)
        loan_rows = collect_loan(datanaru_key, raw, args.loan_size, args.loan_timeout)

    merged, seen, dup = [], set(), []
    for r in kakao_rows + millie_rows + loan_rows + tricky_rows:
        if r["isbn13"] in seen:
            dup.append(f"{r['group']}:{r['isbn13']}")
            continue
        seen.add(r["isbn13"])
        merged.append(r)

    with args.out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "isbn13", "group", "origin", "note"])
        writer.writeheader()
        for n, r in enumerate(merged, start=1):
            writer.writerow({"id": f"B{n:03d}", **r})

    counts = {g: sum(1 for r in merged if r["group"] == g) for g in ("kakao", "millie", "loan", "tricky")}
    print()
    print(f"최종 {len(merged)}개 → {args.out.relative_to(REPO_ROOT)}  {counts}")
    print(f"중복 제외 {len(dup)}개: {', '.join(dup) if dup else '없음'}")
    print(f"원문 → {raw_path.relative_to(REPO_ROOT)} (커밋 금지)")


if __name__ == "__main__":
    main()
