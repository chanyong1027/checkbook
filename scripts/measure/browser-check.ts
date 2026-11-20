/**
 * 1층 브라우저 확인(결정 카드 A2·D5): 실제 화면을 열어 도서관 칸의 첫 표시를 잰다. 위치마다 묶음당 1권(그 묶음의 첫 책).
 *
 * 무엇을 재나 — 모두 페이지 안의 같은 시계(performance.now, 페이지 이동 시작이 0)로 잰다:
 *   - batchResponseMs: 소장이 처음 담긴 배치 응답을 받은 시각(fetch가 끝난 때)
 *   - firstFoundPaintMs: 상태 줄에 "소장 N곳"(N≥1)이 처음 그려진 시각(DOM 변경 뒤 다음 프레임)
 *   - firstBatchStartMs: 첫 배치를 보낸 시각(페이지 로드·위치 확인까지의 몫, 프로토콜 측정의 시작점 밖이라 참고만)
 *   - nonNetworkShareMs = (그려진 시각 − firstBatchStartMs) − 그때까지 배치 응답 시간의 합
 *     = 배치 사이 간격 + 마지막 응답 뒤 그리기. 프로토콜 측정이 놓치는 몫이고 판정에 쓴다
 *   - renderShareMs = firstFoundPaintMs − batchResponseMs: 그중 마지막 응답 뒤 그리기만(자세히 보기용)
 * 판정(규칙 문서 6절 정의 보강·D5): nonNetworkShareMs 최댓값이 300ms 이하면 첫 표시를 프로토콜 값으로 판정한다.
 * 같은 책의 프로토콜 값과 직접 빼지 않는 이유: 두 실행은 정보나루 응답 시간이 서로 달라(수백 ms) 차이에 외부 변동이 섞인다.
 *
 * 실행(Windows PowerShell, 저장소 루트, Chrome 또는 Chromium 필요):
 *   k6 run --quiet --log-format raw --console-output build\measure\<파일>.jsonl -e POSITION=seoul-cityhall scripts\measure\browser-check.ts
 * 측정 캐시를 쓰므로 실행 전에 이 위치 책들의 스냅샷 행을 지운다(README 절차).
 */
import { browser } from 'k6/browser'

type Book = { isbn: string; bin: string; title: string }

const plan = JSON.parse(open('./plan.json'))
const BASE = (__ENV.BASE_URL || 'https://checkbook.site').replace(/\/$/, '')
const POSITION = __ENV.POSITION
const STAGE = __ENV.STAGE || 'before'
const position = plan.positions[POSITION]
if (!position) throw new Error(`POSITION 없음: ${POSITION}`)
const ONLY_ISBN = __ENV.ISBN || null // 스크립트 확인용(측정 목록 밖의 책)

export const options = {
  scenarios: {
    ui: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '20m',
      options: { browser: { type: 'chromium' } },
    },
  },
}

/** 페이지 스크립트보다 먼저 실행된다. fetch를 감싸 배치 응답 시각을, DOM 감시로 그려진 시각을 적는다 */
const INIT_SCRIPT = `
(() => {
  const m = { batches: [], firstFoundPaintMs: null, noneConfirmedPaintMs: null, settledPaintMs: null, statusText: null }
  window.__measure = m
  const origFetch = window.fetch.bind(window)
  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input.url
    if (!url.includes('/api/public-libraries/availability/batch') || !url.includes('mode=NEARBY')) return origFetch(input, init)
    const rec = { offset: Number(new URL(url, location.href).searchParams.get('offset')), startMs: performance.now(), endMs: null, status: null, held: null }
    m.batches.push(rec)
    return origFetch(input, init).then(res => {
      rec.endMs = performance.now()
      rec.status = res.status
      res.clone().json().then(j => {
        rec.held = (j.items || []).filter(i => i.state === 'AVAILABLE' || i.state === 'ON_LOAN').length
      }).catch(() => {})
      return res
    }, err => { rec.endMs = performance.now(); rec.status = 'ERR'; throw err })
  }
  const paint = key => requestAnimationFrame(() => { if (m[key] == null) m[key] = performance.now() })
  const check = () => {
    for (const el of document.querySelectorAll('[role=status]')) {
      const t = el.textContent || ''
      if (!t.includes('곳 확인')) continue
      m.statusText = t
      if (m.firstFoundPaintMs == null && /소장 [1-9]/.test(t)) paint('firstFoundPaintMs')
      if (m.settledPaintMs == null && /가까운 \\d+곳 확인 ·/.test(t)) paint('settledPaintMs')
    }
    if (m.noneConfirmedPaintMs == null && document.body && /이번에 확인한 \\d+곳에는 소장 도서관이 없어요/.test(document.body.textContent || '')) paint('noneConfirmedPaintMs')
  }
  new MutationObserver(check).observe(document, { subtree: true, childList: true, characterData: true })
})()
`

function firstOfEachBin(books: Book[]): Book[] {
  const out: Book[] = []
  for (const bin of ['common', 'middle', 'rare', 'absent']) {
    const b = books.find(x => x.bin === bin)
    if (b) out.push(b)
  }
  return out
}

export default async function () {
  const books: Book[] = ONLY_ISBN
    ? [{ isbn: ONLY_ISBN, bin: 'smoke', title: '' }]
    : firstOfEachBin(plan.books[position.books] as Book[])
  for (const book of books) {
    // 좌표는 accuracy까지 넣고 setGeolocation으로 한 번 더 건다. 11-20 시험에서 newContext의 좌표만으로는
    // 권한은 granted인데 getCurrentPosition이 오류 2(위치 없음)로 실패해 탐색이 시작되지 않았다
    const geolocation = { latitude: position.lat, longitude: position.lon, accuracy: 10 }
    const context = await browser.newContext({ geolocation, permissions: ['geolocation'], locale: 'ko-KR' })
    await context.setGeolocation(geolocation)
    await context.addInitScript(INIT_SCRIPT)
    const page = await context.newPage()
    const startedAt = new Date().toISOString()
    let timedOut = false
    try {
      await page.goto(`${BASE}/book/${book.isbn}`)
      // 탐색이 멈춘 뒤(상태 줄이 "가까운 N곳 확인 ·")까지. 바깥 마감 10초 + 페이지 로드 여유
      await page.waitForFunction(() => (window as unknown as { __measure: { settledPaintMs: number | null } }).__measure?.settledPaintMs != null,
        { timeout: 25_000, polling: 100 })
    } catch {
      timedOut = true
    }
    const m = await page.evaluate(() => (window as unknown as { __measure: unknown }).__measure) as {
      batches: Array<{ offset: number; startMs: number; endMs: number | null; status: number | string | null; held: number | null }>
      firstFoundPaintMs: number | null
      noneConfirmedPaintMs: number | null
      settledPaintMs: number | null
      statusText: string | null
    }
    const heldIdx = m.batches.findIndex(b => (b.held ?? 0) > 0)
    const firstHeld = heldIdx >= 0 ? m.batches[heldIdx] : undefined
    const batchResponseMs = firstHeld?.endMs ?? null
    const firstStart = m.batches[0]?.startMs ?? null
    const fetchSum = (upTo: number) => m.batches.slice(0, upTo + 1)
      .reduce((acc, b) => acc + (b.endMs != null ? b.endMs - b.startMs : 0), 0)
    const nonNetwork = (paint: number | null, upTo: number) =>
      paint != null && firstStart != null && upTo >= 0 ? paint - firstStart - fetchSum(upTo) : null
    console.log(`MEASURE ${JSON.stringify({
      stage: STAGE, pass: 'browser', position: POSITION, isbn: book.isbn, bin: book.bin, startedAt, timedOut,
      firstBatchStartMs: m.batches[0]?.startMs ?? null,
      batchResponseMs,
      firstFoundPaintMs: m.firstFoundPaintMs,
      nonNetworkShareMs: m.firstFoundPaintMs != null
        ? nonNetwork(m.firstFoundPaintMs, heldIdx)
        : nonNetwork(m.noneConfirmedPaintMs, m.batches.length - 1),
      renderShareMs: batchResponseMs != null && m.firstFoundPaintMs != null ? m.firstFoundPaintMs - batchResponseMs : null,
      // 소장 없음으로 끝난 책: 마지막 배치 응답 → "소장 도서관이 없어요"가 그려지기까지
      noneRenderShareMs: m.noneConfirmedPaintMs != null && m.batches.length > 0 && m.batches[m.batches.length - 1].endMs != null
        ? m.noneConfirmedPaintMs - (m.batches[m.batches.length - 1].endMs as number) : null,
      noneConfirmedPaintMs: m.noneConfirmedPaintMs,
      settledPaintMs: m.settledPaintMs,
      batches: m.batches,
      statusText: m.statusText,
    })}`)
    await page.close()
    await context.close()
  }
}
