#!/bin/bash
# open-loop 1런 표준 절차: 재시드 → 드레인 확인 → 거절·관측 스냅샷 → k6(Windows k6.exe, +WireMock 프로브·docker stats 병행) → 요약
# README "Open-loop 절차" 절의 실행 가능한 구현 — 2026-07-11 재스윕(benchmark-results.md §7)에서 11런 검증.
#
# 사용: run-openloop-sweep.sh <RATE> <TIMEUNIT> <RUN(1~2자리 고유)> <라벨(효과rps)> <SCENARIO(코드상태, 예: lazy5)>
#   예) 14.5rps: run-openloop-sweep.sh 29 2s 71 14.5 lazy5   (소수 도착률은 TIMEUNIT으로 표현)
#   원시 산출물: results/<SCENARIO>-raw/ (metrics·probe·stats CSV, rej 스냅샷, k6 로그)
#   요약 JSON:   results/openloop-<SCENARIO>-rate<라벨>-r<RUN>.json (README 네이밍 규칙)
#   무작위 도착(선택, 11-09): 6번째 인자 THIN — RATE는 시도율, 요청 확률 THIN. 예) 15rps 무작위: run-openloop-sweep.sh 150 1s 31 15 m1oci-poisson 0.1
#     실제 도착 수가 이항분포로 흔들려 rps 게이트는 ±2% 대신 ±3σ(√(N·THIN·(1−THIN)))로 본다
set -u
RATE=$1; TIMEUNIT=$2; RUN=$3; LABEL=$4; SCENARIO=$5; THIN=${6:-}
REPO=$(git rev-parse --show-toplevel 2>/dev/null || echo /mnt/c/Users/cyhong/Desktop/checkbook)
RAW=$REPO/scripts/loadtest/results/${SCENARIO}-raw
OUT=$REPO/scripts/loadtest/results/openloop-${SCENARIO}-rate${LABEL}-r${RUN}.json
mkdir -p "$RAW"
cd "$REPO" || { echo "FAIL: REPO 디렉터리 이동 실패 — $REPO"; exit 1; }
# k6는 Windows의 k6.exe로 돈다(WSL interop). 없으면 재시드 전에 멈춘다
command -v k6.exe > /dev/null || { echo "FAIL: k6.exe가 PATH에 없음 — Windows에 k6 설치·WSL interop(PATH 공유) 확인"; exit 1; }

echo "=== RUN=$RUN RATE=$RATE TIMEUNIT=$TIMEUNIT THIN=${THIN:-없음(균일 도착)} (효과 ${LABEL}rps, 코드상태 $SCENARIO) ==="

# [1] 재시드 (스냅샷 TRUNCATE — 유일 ISBN 캐시 우회 전제)
docker exec -i loadtest-postgres psql -U checkbook -d checkbook < scripts/loadtest/seed-libraries.sql > /dev/null 2>&1 || { echo "SEED FAIL"; exit 1; }

# [2] 드레인 확인 — search/publib 풀 queued+active 합 0까지 대기(최대 120s). 0 전 시작 = 런 오염
for i in $(seq 1 60); do
  g=$(curl -s http://localhost:8080/actuator/prometheus | grep -E '^executor_(queued_tasks|active_threads)' | grep -E 'searchExecutor|publicLibraryExecutor' | awk '{s+=$2} END {printf "%d", s}')
  [ "${g:-1}" -eq 0 ] && { echo "drain ok (${i}회 시도)"; break; }
  [ "$i" -eq 60 ] && echo "WARN: drain 미완(잔여 $g)"
  sleep 2
done

# [2-1] 서킷 확인(11-09 추가) — 앞 런(과부하)에서 열린 서킷이 이번 런으로 넘어오면 빠른 실패가 결과를 좋게 보이게 한다.
# 자동 전환이 꺼져 있어(automatic-transition 미설정) 트래픽 없이는 OPEN이 닫히지 않으므로 기다리지 않고 멈춘다.
# 처리: 낮은 도착률로 잠깐 요청을 흘려 HALF_OPEN→CLOSED로 되돌리거나, 앱 재시작 후 다시 예열한다(사람이 판단)
cb=$(curl -sf http://localhost:8080/actuator/prometheus | grep '^resilience4j_circuitbreaker_state{' | grep -v 'state="closed"' | awk '$2+0 > 0')
[ -z "$cb" ] || { echo "FAIL: 닫히지 않은 서킷이 있어 런을 시작하지 않는다:"; echo "$cb"; exit 1; }
echo "circuits ok (all closed)"

# [3] 거절 카운터 before 스냅샷 — 판정 근거이므로 실패/빈 파일이면 런 시작 전에 중단
curl -sf http://localhost:8080/actuator/prometheus | grep '^executor_rejected_total' > "$RAW/rej-before-r${RUN}.txt"
[ -s "$RAW/rej-before-r${RUN}.txt" ] || { echo "FAIL: 거절 카운터 before 스냅샷 실패(빈 파일) — 앱/액추에이터 확인"; exit 1; }

# [3-1] 관측 전 스냅샷(11-08 추가, 관측 전용): PG 커밋 수, CPU 시간(/proc/stat — Docker Desktop WSL2 백엔드라
# WSL과 컨테이너가 같은 커널이다. cpu0·cpu1이 앱+PG 코어), YES24 재시도·선제한 카운터. 실패해도 런은 계속한다
pg_commits() { docker exec loadtest-postgres psql -U checkbook -d checkbook -tAc \
  "select xact_commit from pg_stat_database where datname = 'checkbook'" 2>/dev/null | tr -d '[:space:]'; }
yes24_counters() { curl -sf http://localhost:8080/actuator/prometheus \
  | grep -E '^(resilience4j_retry_calls_total|yes24_rate_limited_total)\{' | grep -E 'name="yes24"|^yes24_rate_limited_total'; }
PG_BEFORE=$(pg_commits); T_BEFORE=$(date +%s.%N)
grep -E '^cpu[01]? ' /proc/stat > "$RAW/procstat-before-r${RUN}.txt"
yes24_counters > "$RAW/yes24-before-r${RUN}.txt"

# [4] 병행 관측: 풀 게이지 폴링 + WireMock bookExist 직접 프로브(하네스 실효지연) + docker stats(BlockIO 포함)
# RUN 재사용 시 이전 시도의 행이 섞이지 않게 관측 파일 truncate
: > "$RAW/probe-r${RUN}.csv"; : > "$RAW/stats-r${RUN}.csv"
./scripts/loadtest/poll-executor-metrics.sh "$RAW/metrics-r${RUN}.csv" & P1=$!
( while :; do t=$(curl -s -o /dev/null --max-time 5 -w '%{time_total}' \
    "http://localhost:8089/api/bookExist?authKey=x&libCode=7&isbn13=9780000000007&format=json"); \
    echo "$(date +%s),$t" >> "$RAW/probe-r${RUN}.csv"; sleep 2; done ) & P2=$!
# 열: 이름,CPU%,메모리,BlockIO(누적 읽기 / 쓰기) — 앞 두 열 위치는 그대로(아래 요약이 p[1]을 읽는다)
( while :; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.BlockIO}}' \
    loadtest-app loadtest-wiremock loadtest-postgres >> "$RAW/stats-r${RUN}.csv" 2>/dev/null; sleep 3; done ) & P3=$!

# [5] k6 (threshold 실패 시 exit!=0 — 붕괴 판정 데이터이므로 계속 진행)
# k6는 Windows 호스트에서 k6.exe로 돈다(WSL 시계 점프 회피, 2026-11-09). CPU 분리는 없다
# (고부하 구간에서 k6/VM 코어 경합 가능성을 첫 고부하 런의 CPU로 확인).
# k6.exe는 /mnt/c/... 경로를 못 연다(요약 저장만 실패하고 exit 0) — 요약 경로는 C:/... 형식으로 넘긴다
OUT_WIN=$(wslpath -m "$OUT") || { echo "FAIL: wslpath 변환 실패 — $OUT"; exit 1; }
k6.exe run -e RUN="$RUN" -e RATE="$RATE" -e TIMEUNIT="$TIMEUNIT" -e THIN="$THIN" \
  --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99),p(99.9)" \
  --summary-export "$OUT_WIN" scripts/loadtest/k6-search-openloop.js > "$RAW/k6-r${RUN}.log" 2>&1
K6EXIT=$?
kill $P1 $P2 $P3 2>/dev/null; wait 2>/dev/null
PG_AFTER=$(pg_commits); T_AFTER=$(date +%s.%N)
grep -E '^cpu[01]? ' /proc/stat > "$RAW/procstat-after-r${RUN}.txt"
yes24_counters > "$RAW/yes24-after-r${RUN}.txt"
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
for n in ["public_library_failed","public_library_incomplete",
          "subscription_failed","identification_failed","search_available","http_req_failed"]:
    print(f"{n}={rate(n)}", end="  ")
print()
# 관측 전용(게이트 아님): YES24 초당 선제한으로 건너뜀, 책 정보가 YES24가 아님(k6가 힌트를 보내 HINT → 밀리는 호출됨)
print(f"new_book_rate_limited={rate('new_book_rate_limited')}  identification_fallback={rate('identification_fallback')}")
# PR-2: searchExecutor 작업은 도서관·밀리 둘뿐 — 거절 증분과 대조할 FAILED 건수(비율 역산 금지: 밀리 분모가 흔들림)
fp=m.get("public_library_failed",{}).get("passes",0); fs=m.get("subscription_failed",{}).get("passes",0)
print(f"FAILED 건수 publib={fp:.0f} sub={fs:.0f} 합={fp+fs:.0f}  (searchExecutor 거절 증분과 대조)")
# HTTP는 성공했는데 불가용인 건수 — 도서관 섹션 SKIPPED(비표준 ISBN·위치 누락 = 하네스 오류) 또는 응답 계약 붕괴.
# 비율이 아니라 건수로 비교: 전송 실패가 섞여도 하네스 고장을 놓치지 않게
sa=m.get("search_available",{}); hf=m.get("http_req_failed",{})
extra=sa.get("fails",0)-hf.get("passes",0)
if extra>0:
    print(f"경고: HTTP 성공인데 불가용 {extra:.0f}건 — 하네스 무효 의심(비표준 ISBN·위치 누락) 또는 응답 계약 붕괴(k6 로그 '응답 계약 붕괴' 확인)")
di=m.get("dropped_iterations",{}).get("count",0)
print(f"dropped_iterations={di}  vus_max={m.get('vus_max',{}).get('max','?')}")
EOF

# [8] 프로브·CPU 요약 — 프로브는 정보나루 bookExist 매핑을 직접 찌른다. med가 몸통 중앙값(400ms)에서 부풀면 하네스 오염 신호.
# 꼬리 확장(0.5% 확률로 600~680ms) 때문에 max는 가끔 680ms 근처까지 튈 수 있다 — med로 판단한다
echo "--- WireMock 프로브(실효 지연) / CPU / 디스크 ---"
python3 - "$RAW/probe-r${RUN}.csv" "$RAW/stats-r${RUN}.csv" <<'EOF'
import sys,statistics
ts=[float(l.split(',')[1]) for l in open(sys.argv[1]) if l.strip()]
if ts: print(f"probe n={len(ts)} med={statistics.median(ts)*1000:.0f}ms max={max(ts)*1000:.0f}ms")
cpu={}; blk={}
for l in open(sys.argv[2]):
    p=l.strip().split(',')
    if len(p)>=2 and p[1].endswith('%'): cpu.setdefault(p[0],[]).append(float(p[1][:-1]))
    if len(p)>=4: blk.setdefault(p[0],[]).append(p[3])
for k,v in cpu.items(): print(f"{k}: cpu med={statistics.median(v):.0f}% max={max(v):.0f}%")
# BlockIO는 컨테이너 기동 뒤 누적값(읽기 / 쓰기) — 런 처음과 끝을 나란히 본다
for k,v in blk.items(): print(f"{k}: blockio 처음={v[0]}  끝={v[-1]}")
EOF

# [9] 관측 전용 요약(11-08 추가): 초당 PG 커밋, iowait 비율, YES24 재시도·선제한 증분
echo "--- PG 커밋 / iowait / YES24 재시도 ---"
python3 - "$PG_BEFORE" "$PG_AFTER" "$T_BEFORE" "$T_AFTER" \
  "$RAW/procstat-before-r${RUN}.txt" "$RAW/procstat-after-r${RUN}.txt" \
  "$RAW/yes24-before-r${RUN}.txt" "$RAW/yes24-after-r${RUN}.txt" <<'EOF'
import sys
pb,pa,tb,ta,sb,sa,yb,ya=sys.argv[1:9]
dt=float(ta)-float(tb)
if pb.isdigit() and pa.isdigit():
    print(f"pg xact_commit +{int(pa)-int(pb)} ({(int(pa)-int(pb))/dt:.1f}/s, {dt:.0f}s)")
else:
    print(f"pg xact_commit 읽기 실패(before={pb!r} after={pa!r})")
def stat(p):
    # cpu user nice system idle iowait irq softirq steal ... (단위: USER_HZ)
    return {l.split()[0]: [int(x) for x in l.split()[1:9]] for l in open(p) if l.strip()}
b,a=stat(sb),stat(sa)
for name in ["cpu","cpu0","cpu1"]:
    if name in a and name in b:
        d=[x-y for x,y in zip(a[name],b[name])]; tot=sum(d) or 1
        print(f"{name}: iowait={d[4]*100/tot:.2f}%  busy={(tot-d[3]-d[4])*100/tot:.1f}%")
def counters(p):
    out={}
    for l in open(p):
        if l.strip():
            k,v=l.rsplit(' ',1); out[k]=float(v)
    return out
cb,ca=counters(yb),counters(ya)
if not ca: print("YES24 카운터 읽기 실패(액추에이터 확인)")
for k in sorted(ca): print(f"{k}: +{ca[k]-cb.get(k,0):.0f}")
EOF

# [10] DB 연결 풀·서킷 요약(11-09 추가, 관측 전용): 폴링 CSV(1초 간격)에서 Hikari 활성·대기 최댓값과
# 런 중 한 번이라도 closed가 아니었던 서킷. 대기(pending)가 0보다 크면 DB 연결 풀(기본 10)이 병목 후보다
echo "--- DB 연결 풀 / 서킷 ---"
python3 - "$RAW/metrics-r${RUN}.csv" <<'EOF'
import sys
act=[]; pend=[]; notclosed={}
for l in open(sys.argv[1]):
    p=l.strip().split(',')
    if len(p)!=3 or p[0]=='epoch': continue
    _,name,val=p
    try: v=float(val)
    except ValueError: continue
    if name.startswith('hikaricp_connections_active'): act.append(v)
    elif name.startswith('hikaricp_connections_pending'): pend.append(v)
    elif name.startswith('resilience4j_circuitbreaker_state') and 'state="closed"' not in name:
        notclosed[name]=notclosed.get(name,0)+1
if act: print(f"hikari active max={max(act):.0f}  pending max={max(pend) if pend else 0:.0f} (표본 {len(act)})")
else: print("hikari 지표 없음(폴링 확인)")
if notclosed:
    for k,n in sorted(notclosed.items()): print(f"서킷 closed 아님: {k} {n}초")
else: print("서킷: 런 내내 closed")
EOF
echo "=== 완료: $OUT ==="
