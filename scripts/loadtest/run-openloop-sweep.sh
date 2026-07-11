#!/bin/bash
# open-loop 1런 표준 절차: 재시드 → 드레인 확인 → 거절 스냅샷 → k6(+WireMock 프로브·docker stats 병행) → 요약
# README "Open-loop 절차" 절의 실행 가능한 구현 — 2026-07-11 재스윕(benchmark-results.md §7)에서 11런 검증.
#
# 사용: run-openloop-sweep.sh <RATE> <TIMEUNIT> <RUN(1~2자리 고유)> <라벨(효과rps)> <SCENARIO(코드상태, 예: lazy5)>
#   예) 14.5rps: run-openloop-sweep.sh 29 2s 71 14.5 lazy5   (소수 도착률은 TIMEUNIT으로 표현)
#   원시 산출물: results/<SCENARIO>-raw/ (metrics·probe·stats CSV, rej 스냅샷, k6 로그)
#   요약 JSON:   results/openloop-<SCENARIO>-rate<라벨>-r<RUN>.json (README 네이밍 규칙)
set -u
RATE=$1; TIMEUNIT=$2; RUN=$3; LABEL=$4; SCENARIO=$5
REPO=$(git rev-parse --show-toplevel 2>/dev/null || echo /mnt/c/Users/cyhong/Desktop/checkbook)
RAW=$REPO/scripts/loadtest/results/${SCENARIO}-raw
OUT=$REPO/scripts/loadtest/results/openloop-${SCENARIO}-rate${LABEL}-r${RUN}.json
mkdir -p "$RAW"
cd "$REPO" || { echo "FAIL: REPO 디렉터리 이동 실패 — $REPO"; exit 1; }

echo "=== RUN=$RUN RATE=$RATE TIMEUNIT=$TIMEUNIT (효과 ${LABEL}rps, 코드상태 $SCENARIO) ==="

# [1] 재시드 (스냅샷 TRUNCATE — 유일 ISBN 캐시 우회 전제)
docker exec -i loadtest-postgres psql -U checkbook -d checkbook < scripts/loadtest/seed-libraries.sql > /dev/null 2>&1 || { echo "SEED FAIL"; exit 1; }

# [2] 드레인 확인 — search/publib 풀 queued+active 합 0까지 대기(최대 120s). 0 전 시작 = 런 오염
for i in $(seq 1 60); do
  g=$(curl -s http://localhost:8080/actuator/prometheus | grep -E '^executor_(queued_tasks|active_threads)' | grep -E 'searchExecutor|publicLibraryExecutor' | awk '{s+=$2} END {printf "%d", s}')
  [ "${g:-1}" -eq 0 ] && { echo "drain ok (${i}회 시도)"; break; }
  [ "$i" -eq 60 ] && echo "WARN: drain 미완(잔여 $g)"
  sleep 2
done

# [3] 거절 카운터 before 스냅샷 — 판정 근거이므로 실패/빈 파일이면 런 시작 전에 중단
curl -sf http://localhost:8080/actuator/prometheus | grep '^executor_rejected_total' > "$RAW/rej-before-r${RUN}.txt"
[ -s "$RAW/rej-before-r${RUN}.txt" ] || { echo "FAIL: 거절 카운터 before 스냅샷 실패(빈 파일) — 앱/액추에이터 확인"; exit 1; }

# [4] 병행 관측: 풀 게이지 폴링 + WireMock bookExist 직접 프로브(하네스 실효지연) + docker stats
# RUN 재사용 시 이전 시도의 행이 섞이지 않게 관측 파일 truncate
: > "$RAW/probe-r${RUN}.csv"; : > "$RAW/stats-r${RUN}.csv"
./scripts/loadtest/poll-executor-metrics.sh "$RAW/metrics-r${RUN}.csv" & P1=$!
( while :; do t=$(curl -s -o /dev/null --max-time 5 -w '%{time_total}' \
    "http://localhost:8089/api/bookExist?authKey=x&libCode=7&isbn13=9780000000007&format=json"); \
    echo "$(date +%s),$t" >> "$RAW/probe-r${RUN}.csv"; sleep 2; done ) & P2=$!
( while :; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
    loadtest-app loadtest-wiremock loadtest-postgres >> "$RAW/stats-r${RUN}.csv" 2>/dev/null; sleep 3; done ) & P3=$!

# [5] k6 (threshold 실패 시 exit!=0 — 붕괴 판정 데이터이므로 계속 진행)
k6 run -e RUN="$RUN" -e RATE="$RATE" -e TIMEUNIT="$TIMEUNIT" \
  --summary-export "$OUT" scripts/loadtest/k6-search-openloop.js > "$RAW/k6-r${RUN}.log" 2>&1
K6EXIT=$?
kill $P1 $P2 $P3 2>/dev/null; wait 2>/dev/null
# k6 기동 자체가 실패하면(바이너리 부재·스크립트 오류) 요약 JSON이 없다 — 유령 런 방지
[ -s "$OUT" ] || { echo "FAIL: k6 요약 JSON 미생성 — k6 로그 tail:"; tail -5 "$RAW/k6-r${RUN}.log"; exit 1; }

# [6] 거절 카운터 after + 증분 (after 실패는 경고 — k6 결과 자체는 유효)
curl -sf http://localhost:8080/actuator/prometheus | grep '^executor_rejected_total' > "$RAW/rej-after-r${RUN}.txt"
[ -s "$RAW/rej-after-r${RUN}.txt" ] || echo "WARN: after 스냅샷 실패 — 거절 증분 판독 불가(아래 증분 무시할 것)"
echo "--- 거절 증분 (과부하 런이면 '섹션 FAILED 합 == 증분' 항등식 확인 — 어긋나면 타임아웃 혼합 레짐) ---"
python3 - "$RAW/rej-before-r${RUN}.txt" "$RAW/rej-after-r${RUN}.txt" <<'EOF'
import sys
def parse(p):
    d={}
    for line in open(p):
        k,v=line.rsplit(' ',1); d[k]=float(v)
    return d
b,a=parse(sys.argv[1]),parse(sys.argv[2])
for k in sorted(a):
    print(f"{k}: +{a[k]-b.get(k,0):.0f}")
EOF

# [7] k6 요약 — 1차 게이트: http rps가 목표 도착률과 ±2% 내인지 먼저 확인(괴리 시 하네스/시계부터 의심)
echo "--- k6 요약 (exit=$K6EXIT) ---"
python3 - "$OUT" <<'EOF'
import json,sys
m=json.load(open(sys.argv[1]))["metrics"]
def rate(n): return f"{m[n]['value']*100:.2f}%" if n in m else "n/a"
d=m["http_req_duration"]
print(f"http rps={m['http_reqs']['rate']:.2f}  p95={d['p(95)']:.0f}ms  med={d['med']:.0f}ms")
for n in ["public_library_failed","public_library_incomplete","used_book_failed",
          "subscription_failed","identification_failed","search_available","http_req_failed"]:
    print(f"{n}={rate(n)}", end="  ")
print()
di=m.get("dropped_iterations",{}).get("count",0)
print(f"dropped_iterations={di}  vus_max={m.get('vus_max',{}).get('max','?')}")
EOF

# [8] 프로브·CPU 요약 — 프로브 med가 명목(356ms)에서 부풀면 하네스 오염 신호
echo "--- WireMock 프로브(실효 지연) / CPU ---"
python3 - "$RAW/probe-r${RUN}.csv" "$RAW/stats-r${RUN}.csv" <<'EOF'
import sys,statistics
ts=[float(l.split(',')[1]) for l in open(sys.argv[1]) if l.strip()]
if ts: print(f"probe n={len(ts)} med={statistics.median(ts)*1000:.0f}ms max={max(ts)*1000:.0f}ms")
cpu={}
for l in open(sys.argv[2]):
    p=l.strip().split(',')
    if len(p)>=2 and p[1].endswith('%'): cpu.setdefault(p[0],[]).append(float(p[1][:-1]))
for k,v in cpu.items(): print(f"{k}: cpu med={statistics.median(v):.0f}% max={max(v):.0f}%")
EOF
echo "=== 완료: $OUT ==="
