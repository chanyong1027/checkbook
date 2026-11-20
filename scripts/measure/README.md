# scripts/measure — 정보나루 지역 조회 1층 측정

정보나루 지역 조회로 공공도서관 칸의 호출을 줄이는 작업의 "전·후" 측정 도구다. 1층은 실제 API·운영 서버·한 사람이 천천히 여는 조건이다.
얼려 둔 `scripts/loadtest/`와는 따로 둔다(그쪽은 고치지 않는다).

| 파일 | 하는 일 |
|---|---|
| `plan.json` | 위치 4곳, 시도별 18권(흔한·중간·드문 5권, 없는 3권), 더 찾기·기준 도서관 줄. 2026-11-20 확정, 측정 끝까지 바꾸지 않는다 |
| `library-explore.ts` | k6 프로토콜 스크립트. 화면과 같은 탐색 고리(`frontend/.../exploreLoop.ts`)를 import해 `/api/search`와 배치를 화면과 같은 순서·주소로 보낸다. 책마다 `MEASURE {...}` 한 줄 |
| `browser-check.ts` | k6 브라우저 스크립트. 실제 화면에서 "소장 배치 응답 → 화면에 그려짐" 시간(렌더링 몫)을 잰다. 위치마다 묶음당 1권 |
| `server-probe.sh` | 운영 서버에서 실행. `read`: 정보나루 호출 수·호출 시간 지표, `reset ISBN...`: 그 책의 스냅샷 행만 삭제 |
| `summarize.py` | 결과 폴더를 판정 규칙대로 표로 묶는다(`test_summarize.py`가 판정 함수 시험) |

## 무엇을 재나 (정의)

- 시작점(0): 상세 화면이 `/api/search`와 첫 배치를 보낸 때. 페이지 로드·위치 확인 시간은 넣지 않는다(브라우저 확인에서 참고로만 적는다).
- 첫 표시: 소장 도서관이 처음 1곳 보인 시각, 또는 소장 없음이 확정된 종료(`EXHAUSTED`·`ROUND_LIMIT`).
- 확정: 탐색이 멈춘 시각(멈춤 사유 함께).
- 호출 수: 회차 앞뒤 서버 지표 `external_budget_used{provider="datanaru"}`의 차이가 주 값이고, 응답의 확인 곳 수는 보조다.
- 판정: 첫 표시 95% 이상 2초 이하·최대 4초 이하, 확정 95% 이상 5초 이하·최대 10초 이하, 미확정은 우리 원인 0·외부 원인 1% 이하. 판정 표본은 캐시 빈 회차 + 기준 도서관 줄이다(두 번째 사람은 따로 적는다).
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

## 측정일 순서

운영을 바꾸는 두 가지(IP 하루 한도를 1,500으로 올리고 되돌리기, 스냅샷 행 삭제)는 **각각 직전에 사용자 확인**을 받는다. 한도를 바꾸는 서버 명령은 그날 따로 정한다(`env_file`은 컨테이너를 만들 때 읽혀 `docker restart`로는 바뀌지 않는다).

서버 보조 스크립트는 저장소 사본에서 꺼내 실행한다(이하 `PROBE`):
```bash
sudo git -C /opt/checkbook/app fetch -q origin && sudo git -C /opt/checkbook/app show origin/main:scripts/measure/server-probe.sh | sudo bash -s -- <read | reset ISBN...>
```
`read` 출력은 측정 PC의 `build\measure\<stage>\probe-<label>-pre.txt`·`-post.txt`로 받는다. `post`는 회차가 끝나고 **15초 뒤**에 읽는다(예산 DB 동기화 10초).

위치 순서는 전 측정 `seoul-cityhall → suwon-station → gangseo-office → chuncheon-cityhall`, 후 측정은 거꾸로다(`plan.json`의 `order`). 위치마다:

1. `PROBE reset <그 위치 책 18권>` → 브라우저 확인(label `<위치>-browser`)
   ```powershell
   k6 run --quiet --log-format raw --console-output build\measure\before\seoul-cityhall-browser.jsonl -e POSITION=seoul-cityhall scripts\measure\browser-check.ts
   ```
2. 회차 1~3(춘천은 1만): `PROBE reset <18권>` → `PROBE read`(pre) → 실행 → 15초 → `PROBE read`(post). label `<위치>-r<회차>`
   ```powershell
   k6 run --quiet --log-format raw --console-output build\measure\before\seoul-cityhall-r1.jsonl -e POSITION=seoul-cityhall -e PASS=first -e ROUND=1 scripts\measure\library-explore.ts
   ```
3. 두 번째 사람(춘천 제외): 행을 지우지 않고 바로 `PASS=second`, label `<위치>-second`(pre·post read).

그다음 한 번씩:
- 더 찾기 끝까지: `PROBE reset 9788924272703` → `PASS=more`(label `more`, pre·post read)
- 기준 도서관: `PROBE reset 9788936434120` → `PASS=reference`(label `reference`, pre·post read). 기준 도서관 `111314`(서울도서관)가 서버 DB에서도 서울시청에 가장 가까운지 먼저 확인한다.

끝나면 같은 날 IP 한도를 되돌리고 확인 명령 결과를 측정 기록에 남긴다. 집계(WSL에서 돌려도 된다 — 시간을 재지 않는다):
```bash
python3 scripts/measure/summarize.py build/measure/before
```

## 양 어림 (측정일 하루, 최악)

- 우리 IP 요청(한도 1,500): 위치 셋 × (브라우저 약 24 + 회차 3 × 18권 × 최대 5 + 두 번째 사람 최대 90) + 춘천 약 114 + 더 찾기 약 42 + 기준 5 ≈ **1,310**. 429가 나오면 그날은 거기서 멈추고 다음 날 잇는다(한도를 더 올리지 않는다).
- 정보나루 호출: 회차 약 3,400 + 브라우저 약 280 + 더 찾기 약 205 ≈ **3,900**(차단선 28,500의 약 14%).

## 알려진 한계

- 사용자 PC → 운영 서버라 사용자 쪽 네트워크가 시간에 들어간다.
- `/api/search`에는 힌트로 제목만 보낸다(화면은 후보의 저자·출판사·표지도 보낸다). 힌트는 YES24가 실패할 때만 쓰여 도서관 칸에는 영향이 없다.
- k6 http 시간 초과는 예외가 아니라 `error_code` 1050 응답으로 온다. 스크립트가 이것을 `RoundDeadlineError`로 바꿔 화면과 같은 `TIME`이 나오게 한다.
- 브라우저 확인의 "그려짐"은 DOM 변경 다음 프레임(`requestAnimationFrame`) 시각이다. 실제 픽셀이 바뀐 때와는 한 프레임 안쪽으로 다를 수 있다.
