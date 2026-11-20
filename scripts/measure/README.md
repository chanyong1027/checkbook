# scripts/measure — 정보나루 지역 조회 1층 측정

정보나루 지역 조회로 공공도서관 칸의 호출을 줄이는 작업의 "전·후" 측정 도구다. 1층은 실제 API·운영 서버·한 사람이 천천히 여는 조건이다.
얼려 둔 `scripts/loadtest/`와는 따로 둔다(그쪽은 고치지 않는다).

| 파일 | 하는 일 |
|---|---|
| `plan.json` | 위치 4곳(서울시청·안산시청·강서구청·원주시청)과 그 10km 원이 걸치는 시도, 시도별 18권(흔한·중간·드문 5권, 없는 3권), 더 찾기·기준 도서관 줄, 정보나루 공지 기관. 2026-11-20 확정(11-21 위치 둘 변경), 측정 끝까지 바꾸지 않는다 |
| `library-explore.ts` | k6 프로토콜 스크립트. 화면과 같은 탐색 고리(`frontend/.../exploreLoop.ts`)를 import해 `/api/search`와 배치를 화면과 같은 순서·주소로 보낸다. 책마다 `MEASURE {...}` 한 줄 |
| `browser-check.ts` | k6 브라우저 스크립트. 실제 화면에서 "소장 배치 응답 → 화면에 그려짐" 시간(렌더링 몫)을 잰다. 위치마다 묶음당 1권 |
| `server-probe.sh` | 운영 서버에서 실행. `read`: 정보나루 호출 수·호출 시간 지표, `reset ISBN...`: 그 책의 스냅샷 행만 삭제 |
| `api_check.py` | 순서 4(구현 전 API 확인). **운영 서버에서** 정보나루 지역 조회의 응답 꼴(D1)·갈림 규칙 1·2(호출 수·단계 시간)·규칙 3(목록에 없는 도서관이 정말 없는가)을 모으고, 측정 PC에서 `--summarize`로 판정한다. 아래 "API 확인" 절 |
| `summarize.py` | 결과 폴더를 판정 규칙대로 표로 묶는다(`test_summarize.py`가 판정 함수 시험) |

## 무엇을 재나 (정의)

- 시작점(0): 상세 화면이 `/api/search`와 첫 배치를 보낸 때. 페이지 로드·위치 확인 시간은 넣지 않는다(브라우저 확인에서 참고로만 적는다).
- 첫 표시: 소장 도서관이 처음 1곳 보인 시각, 또는 소장 없음이 확정된 종료(`EXHAUSTED`·`ROUND_LIMIT`).
- 확정: 탐색이 멈춘 시각(멈춤 사유 함께).
- 호출 수: 회차 앞뒤 서버 지표 `external_budget_used{provider="datanaru"}`의 차이가 주 값이고, 응답 항목 수의 합(배치마다 서버가 확인한 곳 수)은 보조다.
- 판정: 첫 표시 95% 이상 2초 이하·최대 4초 이하, 확정 95% 이상 5초 이하·최대 10초 이하(`TIME`을 뺀 조회 전부 — 확인 못 한 칸이 남은 조회도 넣는다. 확정된 조회만의 값은 참고로 따로 표시), 미확정은 우리 원인 0·외부 원인 1% 이하. 판정 표본은 캐시 빈 회차 + 기준 도서관 줄이다(두 번째 사람은 따로 적는다). `TIME` 종료는 어느 쪽으로도 세지 않고 따로 적는다.
- 외부 원인: 확인 못 함 사유가 `TIMEOUT`·`UPSTREAM_ERROR`이고, 그 회차 서버 지표의 "1.07초 넘는 정보나루 호출 + 성공 아닌 호출" 수가 그 칸 수 이상일 때. 지표가 없으면 "판정 보류"다(우리 원인으로 세지 않는다).
- 브라우저 확인의 판정 값은 "네트워크 밖 몫" = (그려진 때 − 첫 배치 보낸 때) − 그때까지 배치 응답 시간의 합(배치 사이 간격 + 그리기)이다. 최댓값이 300ms를 넘으면 첫 표시에 위치별 최댓값을 더해 판정한다.
- 규칙의 근거와 책 목록을 고른 방법은 저장소 밖 작업 기록(`notes/todo/2026-11-20-지역조회-측정-규칙과-책목록.md`)에 있다.

## 준비

- Windows PowerShell에서 저장소 루트로 간다. **WSL 안에서 k6를 돌리지 않는다**(시계가 튀어 시간이 오염된다, 2026-11-09 기록). 스크립트는 `/mnt/c` 아래에 있어야 Windows k6가 읽는다.
- k6 v2.0.0 이상(`k6 version`), 브라우저 확인에는 Chrome 또는 Chromium.
- 결과 폴더: `build\measure\before\`(후 측정은 `after\`). `build/`는 git에서 빠진다.
- 스크립트 확인(측정 목록 밖의 책 한 권, 측정 캐시를 건드리지 않음):
  ```powershell
  k6 run --quiet --log-format raw --console-output build\measure\smoke.jsonl -e POSITION=seoul-cityhall -e PASS=smoke scripts\measure\library-explore.ts
  k6 run --quiet --log-format raw --console-output build\measure\smoke-browser.jsonl -e POSITION=seoul-cityhall -e ISBN=9791161571188 scripts\measure\browser-check.ts
  ```

## API 확인(순서 4, 구현 전 한 번)

운영 서버에서 돌린다(규칙 2의 대상이 운영 서버가 정보나루를 부르는 시간이라서). 운영 설정·DB는 바꾸지 않는다. 정보나루 약 460회, 10분 안팎. 이 호출은 앱의 공급자 예산에는 잡히지 않는다(같은 키라 정보나루 쪽 하루 사용량에는 들어간다).
```bash
D=$(mktemp -d)
sudo git -C /opt/checkbook/app fetch -q origin
sudo git -C /opt/checkbook/app show origin/main:scripts/measure/api_check.py > "$D/api_check.py"
sudo git -C /opt/checkbook/app show origin/main:scripts/measure/plan.json > "$D/plan.json"
sudo python3 "$D/api_check.py" --plan "$D/plan.json" --out "$D/api-check.jsonl"
```
알려진 한계: 스크립트(urllib)는 호출마다 TLS 연결을 새로 맺어 연결을 재사용하는 앱보다 단계 시간이 조금 비관 쪽이다. 둘째 쪽부터는 차례로 받지만 쪽 크기 1000이면 시도마다 한 쪽이다. 강원처럼 시도가 작으면 "목록에 없는 가까운 도서관"이 5곳보다 적은 책이 있어 표본이 90보다 작고, 같은 도서관이 책마다 되풀이되므로 거짓 "없음" 비율의 상한은 참고값이다. 공지 기관은 이름(`names`)으로만 가려 멈춤을 판정하고, 주소의 도시(`cities`)는 멈춘 뒤 원인을 볼 때만 쓴다.

결과 파일을 측정 PC의 `build\measure\api-check\`로 가져와 판정한다: `python3 scripts/measure/api_check.py --summarize build/measure/api-check/api-check.jsonl`. 판정 규칙은 결정 카드 B2(11-21 규칙 3 고침: 정보나루 공지 기관의 어긋남은 따로 세고 멈추지 않음). 규칙 밖 결과면 그 항목만 멈추고 묻는다.

## 측정일 순서

맨 먼저 `PROBE read`를 한 번 돌려 `external_budget_used{provider="datanaru"}`와 `http_client_requests_seconds_count{client_name="data4library.kr",...}` 줄이 나오는지 본다. `WARN`이 나오면 라벨 가정을 고친 뒤 시작한다(없으면 외부 원인 판정이 전부 보류된다).

운영을 바꾸는 두 가지(IP 하루 한도를 1,500으로 올리고 되돌리기, 스냅샷 행 삭제)는 **각각 직전에 사용자 확인**을 받는다. 한도를 바꾸는 서버 명령은 그날 따로 정한다(`env_file`은 컨테이너를 만들 때 읽혀 `docker restart`로는 바뀌지 않는다).

서버 보조 스크립트는 저장소 사본에서 꺼내 실행한다(이하 `PROBE`):
```bash
sudo git -C /opt/checkbook/app fetch -q origin && sudo git -C /opt/checkbook/app show origin/main:scripts/measure/server-probe.sh | sudo bash -s -- <read | reset ISBN...>
```
`read` 출력은 측정 PC의 `build\measure\<stage>\probe-<label>-pre.txt`·`-post.txt`로 받는다. 예산 지표는 칸마다 10초 간격으로 DB와 따로 맞추므로 고정 대기로는 동기화를 보장할 수 없다. 그래서 `read`는 11초 간격으로 다시 읽어 ① 떠 있는 앱 칸(`docker ps`)이 모두 응답하고 ② 정보나루 예산 값이 그대로이며 칸끼리 같고 ③ 동기화 실패 카운터가 늘지 않았고 ④ DB(`external_call_budget`의 오늘 `DATANARU`) 값이 모든 칸의 게이지와 같을 때만 값을 낸다(최대 5번, 약 45초). ④가 모든 칸의 DB 쓰기와 읽기가 끝났다는 확인이다(안 쓴 증가분이 있으면 게이지가 DB보다 크다). 끝내 안 맞으면 값을 내지 않고 **실패(종료 코드 1)**하니, 잠시 뒤 다시 읽는다. 실행 직후 바로 `read`해도 된다.

위치 순서는 전 측정 `seoul-cityhall → ansan-cityhall → gangseo-office → wonju-cityhall`, 후 측정은 거꾸로다(`plan.json`의 `order`). 위치마다:

1. `PROBE reset <그 위치 책 18권>` → 브라우저 확인(label `<위치>-browser`)
   ```powershell
   k6 run --quiet --log-format raw --console-output build\measure\before\seoul-cityhall-browser.jsonl -e POSITION=seoul-cityhall scripts\measure\browser-check.ts
   ```
2. 회차 1~3(원주는 1만): `PROBE reset <18권>` → `PROBE read`(pre) → 실행 → `PROBE read`(post). label `<위치>-r<회차>`
   ```powershell
   k6 run --quiet --log-format raw --console-output build\measure\before\seoul-cityhall-r1.jsonl -e POSITION=seoul-cityhall -e PASS=first -e ROUND=1 scripts\measure\library-explore.ts
   ```
3. 두 번째 사람(원주 제외): 행을 지우지 않고 바로 `PASS=second`, label `<위치>-second`(pre·post read).

그다음 한 번씩:
- 더 찾기 끝까지: `PROBE reset 9788924272703` → `PASS=more`(label `more`, pre·post read)
- 기준 도서관: `PROBE reset 9788936434120` → `PASS=reference`(label `reference`, pre·post read). 기준 도서관 `111314`(서울도서관)가 서버 DB에서도 서울시청에 가장 가까운지 먼저 확인한다.

요청 제한 429에 걸리면 스크립트가 남은 책을 열지 않고 `aborted` 줄을 남긴 뒤 **0이 아닌 코드로 끝난다**(그대로 다음 회차로 넘어가지 않는다). 집계는 그 조회를 표본에서 빼고 판정을 보류한다. `IP_DAILY_LIMIT`(우리 IP 하루 한도)이면 그날은 거기서 멈추고 다음 날 그 회차부터 다시 잰다. `NGINX_RATE_LIMIT`(분당·초당 제한)이면 1분 쉬고 그 회차를 처음부터 다시 잰다(같은 일이 또 나면 책 사이 쉼을 늘린다).

다시 잴 때(회차 종류와 상관없이):
1. 중단된 실행의 jsonl과 probe 파일을 `build\measure\<stage>\aborted\`로 옮긴다(`--console-output`은 이어 쓰므로, 옮기지 않으면 중단 줄이 남아 그날 판정이 계속 보류된다). 그다음 같은 label로 다시 잰다.
2. `first`(캐시 빈 회차): 그 전에 연 책들이 이미 스냅샷을 채웠으므로 **반드시 `PROBE reset`부터** 하고(다음 날 이어도 스냅샷이 24시간이라 같다) pre·실행·post를 다시 받는다.
3. `second`(두 번째 사람): 캐시가 찬 상태가 조건이라 reset 뒤 바로 재면 안 된다. `PROBE reset` → `PASS=first`로 준비 실행 1번(label `<위치>-prep`, 집계에 쓰지 않으니 `build\measure\<stage>\` 밖에 저장) → `PROBE read`(pre — 준비 실행의 호출이 DB와 모든 칸 예산 지표에 다 반영될 때까지 `read`가 스스로 기다리고, 확인하지 못하면 종료 코드 1로 실패하니 다시 읽는다) → `PASS=second` → `PROBE read`(post).

중단 확인: 11-20 k6 v2.0.0에서 async 함수 안 `exec.test.abort()`가 종료 코드 108로 끝나는 것을 봤다.

후 측정(지역 조회 구현 뒤)에는 `reset`이 지역 목록 표의 그 ISBN 행도 지워야 한다. 표 이름이 정해지는 구현 PR에서 `server-probe.sh`에 더한다.

끝나면 같은 날 IP 한도를 되돌리고 확인 명령 결과를 측정 기록에 남긴다. 집계(WSL에서 돌려도 된다 — 시간을 재지 않는다):
```bash
python3 scripts/measure/summarize.py build/measure/before
```

## 양 어림 (측정일 하루, 최악)

- 우리 IP 요청(한도 1,500, 책 한 권 최대 5 = 검색 1 + 배치 4): 위치 셋 × (브라우저 약 24 + 회차 3 × 18권 × 최대 5 + 두 번째 사람 최대 90) + 원주 약 114 + 더 찾기 약 42 + 기준 5 ≈ **1,310**. 429가 나오면 그날은 거기서 멈추고 다음 날 잇는다(한도를 더 올리지 않는다).
- 정보나루 호출: 회차 약 3,400 + 브라우저 약 280 + 더 찾기 약 205 ≈ **3,900**(차단선 28,500의 약 14%).

## 알려진 한계

- 사용자 PC → 운영 서버라 사용자 쪽 네트워크가 시간에 들어간다.
- 외부 원인 근거(느린·실패 정보나루 호출)는 회차 전체의 서버 지표 합이라 측정한 ISBN의 요청과 하나씩 묶이지 않는다. 같은 시간의 실사용·도서관 목록 배치 작업 호출이 근거로 섞일 수 있다(D3이 정한 방식의 한계, 서버 지표로는 요청별로 나눌 수 없다). 회차가 4분 안팎으로 짧고 외부 원인 허용이 1%라 판정에 미치는 폭은 작다고 보았고, 외부 원인으로 분류된 조회는 시각과 함께 결과에 그대로 적는다.
- 서버 예산 차이에는 같은 시간의 실사용과 도서관 목록 배치 작업(`libSrch` 등) 호출도 섞인다. 캐시 빈 회차에서 응답 항목 수의 합(서버가 확인한 곳 수)과 다르면 집계 표에 `⚠예산≠항목 수`가 붙는다.
- 더 찾기 줄은 `ERROR`·`CIRCUIT_OPEN`이나 60번째 탐색에서 멈춘다. 화면은 이때도 더 찾기를 열어 두지만 사람이 장애 중에 끝없이 누르지는 않는다고 보았다.
- `/api/search`에는 힌트로 제목만 보낸다(화면은 후보의 저자·출판사·표지도 보낸다). 힌트는 YES24가 실패할 때만 쓰여 도서관 칸에는 영향이 없다.
- k6 http 시간 초과는 예외가 아니라 `error_code` 1050 응답으로 온다. 스크립트가 이것을 `RoundDeadlineError`로 바꿔 화면과 같은 `TIME`이 나오게 한다.
- 브라우저 확인의 "그려짐"은 DOM 변경 다음 프레임(`requestAnimationFrame`) 시각이다. 실제 픽셀이 바뀐 때와는 한 프레임 안쪽으로 다를 수 있다.
