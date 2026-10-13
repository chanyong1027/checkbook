#!/usr/bin/env python3
"""카카오 책 검색으로 비교용 ISBN 셋을 만든다 (작업 지시서 T0-1).

실제 서비스 흐름을 그대로 따른다: 사용자가 키워드를 치면 카카오 후보 목록(sort=accuracy, size=10)이 뜨고,
그중 한 권을 고르면 그 ISBN으로 상세 조회가 일어난다. 그래서 검색어마다 상위 take_top개의 ISBN을 뽑는다.

입력:  kakao-seed-keywords.csv (id, keyword, group, take_top, why)
출력:  isbns-from-kakao.csv       — ISBN과 출처만 (커밋 가능)
       notes/incidents/aladin-sunset/raw/kakao-seed-<날짜>.jsonl — 제목·저자 등 원문 (로컬 전용, 커밋 금지)

표준 라이브러리만 쓴다. 키는 저장소 루트 .env의 KAKAO_BOOK_REST_API_KEY.
"""

import argparse
import csv
import http.client
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_KEYWORDS = HERE / "kakao-seed-keywords.csv"
DEFAULT_OUT = HERE / "isbns-from-kakao.csv"
RAW_DIR = REPO_ROOT / "notes" / "incidents" / "aladin-sunset" / "raw"

KAKAO_URL = "https://dapi.kakao.com/v3/search/book"
PAGE_SIZE = 10  # 프런트 BookSearchStep의 PAGE_SIZE와 동일


def load_env_key(name: str) -> str:
    env_file = REPO_ROOT / ".env"
    if not env_file.exists():
        sys.exit(f".env 파일이 없습니다: {env_file}")
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            value = value.strip().strip('"').strip("'")
            if value:
                return value
    sys.exit(f".env에 {name} 값이 없습니다")


def isbn10_to_isbn13(isbn10: str) -> str:
    """InputNormalizer.isbn10ToIsbn13과 같은 계산 (978 접두 + 체크섬 재계산)."""
    base = "978" + isbn10[:9]
    total = sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(base))
    return base + str((10 - total % 10) % 10)


def split_isbn(raw: str):
    """카카오 isbn 필드는 'ISBN10 ISBN13'처럼 공백으로 붙어 올 수 있다.
    반환: (isbn13, isbn10, flag) — flag는 both / isbn13_only / isbn10_only / none
    """
    parts = [p.strip() for p in (raw or "").split() if p.strip()]
    isbn13 = next((p for p in parts if len(p) == 13 and p.isdigit()), "")
    # 앱의 InputNormalizer와 같은 규칙: 숫자 9자리 + 숫자 또는 X
    isbn10 = next((p for p in parts if re.fullmatch(r"\d{9}[0-9Xx]", p)), "")
    if isbn13 and isbn10:
        return isbn13, isbn10, "both"
    if isbn13:
        return isbn13, "", "isbn13_only"
    if isbn10:
        # 앱은 ISBN10만 있는 후보도 /api/search에서 ISBN13으로 바꿔 조회한다
        return isbn10_to_isbn13(isbn10), isbn10, "isbn10_only"
    return "", "", "none"


def kakao_search(api_key: str, query: str, timeout: float):
    params = urllib.parse.urlencode(
        {"query": query, "sort": "accuracy", "page": 1, "size": PAGE_SIZE}
    )
    req = urllib.request.Request(
        f"{KAKAO_URL}?{params}",
        headers={"Authorization": f"KakaoAK {api_key}", "Accept": "application/json"},
    )
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    elapsed_ms = round((time.monotonic() - started) * 1000)
    return body, elapsed_ms


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keywords", type=Path, default=DEFAULT_KEYWORDS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--sleep", type=float, default=1.0, help="호출 간격(초)")
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--dry-run", action="store_true", help="API를 부르지 않고 입력만 검증")
    args = parser.parse_args()

    with args.keywords.open(encoding="utf-8", newline="") as f:
        keywords = list(csv.DictReader(f))
    for row in keywords:
        int(row["take_top"])  # 형식 검증
    print(f"검색어 {len(keywords)}개, 목표 ISBN 최대 {sum(int(r['take_top']) for r in keywords)}개")
    if args.dry_run:
        print("dry-run: 입력 형식 정상, API 호출 없음")
        return

    api_key = load_env_key("KAKAO_BOOK_REST_API_KEY")
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    raw_path = RAW_DIR / f"kakao-seed-{stamp}.jsonl"

    seen = {}            # isbn13 -> 처음 나온 출처
    out_rows = []
    stats = {"calls": 0, "failed": 0, "no_isbn": 0, "isbn10_only": 0, "duplicate": 0}

    with raw_path.open("w", encoding="utf-8") as raw:
        for i, row in enumerate(keywords):
            if i > 0:
                time.sleep(args.sleep)
            kid, keyword, take_top = row["id"], row["keyword"], int(row["take_top"])
            try:
                body, elapsed_ms = kakao_search(api_key, keyword, args.timeout)
                stats["calls"] += 1
            except urllib.error.HTTPError as e:
                stats["failed"] += 1
                print(f"[{kid}] HTTP {e.code} — {keyword}")
                if e.code in (401, 403):
                    sys.exit("인증 실패: KAKAO_BOOK_REST_API_KEY를 확인하세요")
                if e.code == 429:
                    sys.exit("카카오 호출 한도 초과(429): 중단합니다")
                continue
            except (OSError, http.client.HTTPException, ValueError) as e:
                # URLError·연결 끊김·불완전 응답·JSON 파싱 실패를 모두 실패로 기록하고 계속 (예외 메시지에 URL·키는 없음)
                stats["failed"] += 1
                print(f"[{kid}] 호출 실패 — {keyword}: {type(e).__name__}: {e}")
                continue

            docs = body.get("documents") or []
            raw.write(json.dumps({"id": kid, "keyword": keyword, "elapsed_ms": elapsed_ms,
                                  "total_count": (body.get("meta") or {}).get("total_count"),
                                  "documents": docs}, ensure_ascii=False) + "\n")

            taken = 0
            for rank, doc in enumerate(docs, start=1):
                if taken >= take_top:
                    break
                isbn13, isbn10, flag = split_isbn(doc.get("isbn"))
                if flag == "none":
                    stats["no_isbn"] += 1
                    continue  # 사용자가 골라도 상세 조회를 못 하는 후보라 건너뛴다
                taken += 1
                if flag == "isbn10_only":
                    stats["isbn10_only"] += 1
                if isbn13 in seen:
                    stats["duplicate"] += 1
                    continue
                seen[isbn13] = f"{kid}#{rank}"
                out_rows.append({
                    "isbn13": isbn13,
                    "isbn10": isbn10,
                    "isbn_flag": flag,
                    "origin": f"kakao:{kid}#{rank}",
                    "group": row["group"],
                    "keyword": keyword,
                })
            print(f"[{kid}] {keyword}: 결과 {len(docs)}건 중 {taken}개 채택 ({elapsed_ms}ms)")

    with args.out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "isbn13", "isbn10", "isbn_flag", "origin", "group", "keyword"])
        writer.writeheader()
        for n, r in enumerate(out_rows, start=1):
            writer.writerow({"id": f"I{n:03d}", **r})

    print()
    print(f"고유 ISBN {len(out_rows)}개 → {args.out.relative_to(REPO_ROOT)}")
    print(f"원문 → {raw_path.relative_to(REPO_ROOT)} (커밋 금지)")
    print(f"호출 {stats['calls']}, 실패 {stats['failed']}, ISBN 없는 후보 건너뜀 {stats['no_isbn']}, "
          f"ISBN10만 있음 {stats['isbn10_only']}, 중복 제외 {stats['duplicate']}")


if __name__ == "__main__":
    main()
