# 부하테스트 가이드

외부 API를 WireMock으로 대체한 compose 격리 환경에서 k6로 `/api/search`에 부하를 건다.
목적: "풀이 동시 요청 1건 기준으로 사이징됨" 가설의 임계점 수치화 + fault 시 자원 누수 관측.
측정 결과는 반드시 **측정 시점의 커밋 해시**와 함께 `notes/todo/benchmark-results.md`에 기록한다.

## 측정 환경 원칙

- **운영 EC2에서 부하테스트 금지** — 운영 DB 오염(시드가 TRUNCATE 포함) + t3 버스터블 크레딧 변동으로 재현성 없음.
- compose가 운영 스펙을 근사: app `cpus:2, mem:1g` (t3.micro). 단 **크레딧 소진 상태는 재현 불가** — 절대 수치가 아니라 동일 환경 내 before/after 상대 비교가 목적.
- 실행 위치: compose·k6는 Windows, 수집 스크립트(bash)는 WSL — Docker Desktop이 포트를 양쪽 localhost에 공개하므로 둘 다 같은 앱을 본다.

## 판정 기준 (상정 SLO — 진단·처방의 성공 기준)

| 축 | 지표 | 목표 (VU=4~5 정상 부하) |
|---|---|---|
| 품질(주지표) | PUBLIC_LIBRARY FAILED율 | < 5% |
| 지연 | p95 / p99 | < 3.0s / < 3.5s |
| 포화(원인) | executor queued/active, hikari pending | 처방② 후 queued 상한 존재 |

주의: 이 시스템은 데드라인+graceful degradation 때문에 **느려지는 대신 섹션을 비워서 응답**한다.
→ latency만 보면 문제가 안 보이고, FAILED율이 주지표다 (k6 커스텀 메트릭 `public_library_failed`).
3.0s 예산은 [추정] 등급 초기 SLO 가설 (근거: notes/decisions 5/22 문서 교정 주석).

## 사전 준비 (1회)

1. Docker Desktop 실행 확인
2. k6 설치 (Windows PowerShell): `winget install k6 --source winget`
3. 그래프용 matplotlib (WSL): `pip3 install matplotlib` (또는 `sudo apt install python3-matplotlib`)

## 매 실행 순서

```bash
# [1] compose 기동 (코드 바뀌었으면 --build 필수. 첫 빌드 수 분 소요)
docker compose -f scripts/loadtest/docker-compose.loadtest.yml up -d --build
docker logs -f loadtest-app        # "Started CheckbookApplication" 확인 후 Ctrl+C

# [2] 시드 (매 측정 전 재실행 — 스냅샷 TRUNCATE 포함)
# ⚠️ 반드시 WSL(bash)에서 실행 — PowerShell의 Get-Content 파이프는 UTF-8 한글을 CP949로 깨뜨려
#    도서관 이름이 mojibake로 저장된다 (측정엔 무해하나 데이터가 지저분해짐)
docker exec -i loadtest-postgres psql -U checkbook -d checkbook < scripts/loadtest/seed-libraries.sql

# [3] 동작 확인
curl -s "http://localhost:8089/api/bookExist?authKey=x&libCode=1&isbn13=9781111111111&format=json"
# → {"response":{"result":{"hasBook":"Y","loanAvailable":"Y"}}}
curl -s "http://localhost:8080/api/search?q=9781111111111&lat=37.5665&lon=126.9780" | head -c 300
# → publicLibraries에 부하테스트도서관 20곳

# [4] 측정: 수집(WSL) → k6(Windows) → 그래프(WSL)
mkdir -p scripts/loadtest/results
./scripts/loadtest/poll-executor-metrics.sh scripts/loadtest/results/<시나리오>-metrics.csv &   # WSL
k6 run --summary-export scripts/loadtest/results/<시나리오>.json scripts/loadtest/k6-search-rampup.js  # Windows
kill %1                                                                                        # WSL
python3 scripts/loadtest/plot-metrics.py scripts/loadtest/results/<시나리오>-metrics.csv        # WSL
```

pgAdmin으로 들여다보기: localhost:**5433**, checkbook/checkbook (일회용 DB — 로컬 개발 DB와 별개).

## 결과 파일 네이밍 규칙 (before/after 비교의 생명)

| 접미사 | 코드 상태 |
|---|---|
| `*-baseline` | 처방 전 main (3/20, 무제한 큐) |
| `*-resized` | 처방① 적용 (12/40, 무제한 큐) |
| `*-bounded-abort` / `*-bounded-callerruns` | 처방② 적용 + 거절 정책 A/B |

## Fault Injection 절차 (시나리오명: fault-<코드상태>)

```bash
./scripts/loadtest/poll-executor-metrics.sh scripts/loadtest/results/fault-<코드상태>-metrics.csv &
k6 run --summary-export scripts/loadtest/results/fault-<코드상태>.json scripts/loadtest/k6-search-fault.js &
sleep 60 && ./scripts/loadtest/inject-datanaru-delay.sh 1900
sleep 120 && ./scripts/loadtest/reset-datanaru-delay.sh
wait %2
kill %1
python3 scripts/loadtest/plot-metrics.py scripts/loadtest/results/fault-<코드상태>-metrics.csv
```

관측 포인트: 주입 구간(60~180s)의 `executor_queued_tasks` 추이, 해제(180s) 이후 회복 시간.
대조 실험: `inject-datanaru-delay.sh 5000` → read-timeout(2000ms) 실패 누적 → CB OPEN → stale 폴백(빠른 FAILED).
**"CB는 실패엔 강하지만 느린 성공(1900ms)에는 무력"**이 핵심 대비.

## 외부 API 레이턴시 실측 (measure-external-latency.sh)

EC2에 SSM 세션으로 접속해서 실행 (운영과 동일 네트워크 경로 — 타임아웃·WireMock 분포의 근거):

```bash
curl -fsSL https://raw.githubusercontent.com/chanyong1027/checkbook/main/scripts/loadtest/measure-external-latency.sh -o /tmp/mel.sh
bash /tmp/mel.sh 100    # 약 4~5분, API별 p50/p95/p99 요약 출력. 주간/야간 각 1회
```

## 주의

- k6 스크립트가 요청마다 유일한 ISBN을 생성 → 스냅샷 캐시(24h TTL)를 우회해 매 요청이 fan-out 20건을 실제 실행한다. **재실행 전 시드를 다시 돌려 스냅샷을 TRUNCATE할 것.**
- WireMock의 datanaru 지연 분포는 `measure-external-latency.sh` 실측값 기준 (측정 전엔 임시 lognormal 400ms).
- featured 워밍업이 기동 시 WireMock의 빈 응답 스텁에 부딪히는 것은 정상 (검색 부하와 무관).
- 코드 상태를 바꿔 재측정할 때는 반드시 `--build`로 이미지 재빌드 + 커밋 해시 기록.
