/**
 * 1층 측정(실제 API, 한 사람, 천천히): 상세 화면을 연 것처럼 /api/search와 주변 도서관 탐색을 함께 보내고
 * 첫 표시·확정 시간과 탐색 결과를 책마다 한 줄(JSON)로 남긴다.
 *
 * 탐색 고리는 화면과 같은 함수(runExploreRound)를 import해 쓴다(결정 카드 D5). 이 스크립트가 정하는 것은
 * "요청을 어떻게 보내나"뿐이다 — 화면의 getNearbyLibraryBatch·searchMain과 같은 주소·파라미터를 만든다.
 *
 * 실행(Windows PowerShell, 저장소 루트에서 — WSL 안에서 돌리지 않는다. 시계가 튀어 시간이 오염됨):
 *   k6 run --quiet --log-format raw --console-output build\measure\<파일>.jsonl -e POSITION=seoul-cityhall -e PASS=first -e ROUND=1 scripts\measure\library-explore.ts
 * 환경 변수:
 *   BASE_URL  기본 https://checkbook.site
 *   POSITION  plan.json positions의 키
 *   PASS      first(캐시 빈 회차) | second(두 번째 사람, 행을 지우지 않고 바로 이어서) | more(더 찾기 끝까지) | reference(기준 도서관 모드)
 *             | smoke(스크립트 확인용 한 권, 측정 목록 밖의 ISBN — 측정 캐시를 건드리지 않게)
 *   ISBN      smoke에서 열 책(기본 9791161571188 불편한 편의점, 후보였지만 목록에 안 든 책)
 *   ROUND     회차 번호(기록용)
 *   STAGE     before | after(기록용)
 */
import http from 'k6/http'
import { sleep } from 'k6'
import exec from 'k6/execution'

import { RoundDeadlineError, runExploreRound, type ExploreResult } from '../../frontend/src/components/library/exploreLoop.ts'
import { INITIAL_STATE, type ExplorerState } from '../../frontend/src/components/library/nearbyExplorer.ts'

type Book = { isbn: string; bin: string; title: string }
type Origin = { lat: number; lon: number } | { referenceLibCode: string }

const plan = JSON.parse(open('./plan.json'))
const BASE = (__ENV.BASE_URL || 'https://checkbook.site').replace(/\/$/, '')
const PASS = __ENV.PASS || 'first'
const ROUND = Number(__ENV.ROUND || '1')
const STAGE = __ENV.STAGE || 'before'
const POSITION = PASS === 'more' || PASS === 'reference' ? plan.extras[PASS].position : __ENV.POSITION
const position = plan.positions[POSITION]
if (!position) throw new Error(`POSITION 없음: ${POSITION}`)
if (!['first', 'second', 'more', 'reference', 'smoke'].includes(PASS)) throw new Error(`PASS 모름: ${PASS}`)

/** 책 사이 쉼(초). nginx /api/search 분당 30을 넘지 않게 2초(규칙 문서 5절) */
const GAP_SECONDS = 2
/** 사용자가 "더 찾기"를 누르기까지의 쉼(초). 시간이 아니라 호출 수를 보는 줄이라 짧게 둔다 */
const MORE_CLICK_SECONDS = 1
/** 더 찾기 횟수 상한. 서울 10km 약 205곳 ÷ 5 = 41배치라 넉넉히. 장애로 같은 곳을 끝없이 다시 찾지 않게 */
const MORE_ROUND_LIMIT = 60
/**
 * 요청 제한 429는 측정 절차의 실패라 그 회차를 거기서 멈춘다(집계는 "측정 중단"으로 빼고 다시 잰다, 규칙 문서 6절).
 * DAILY_REQUEST_LIMIT_EXCEEDED: 우리 IP 하루 한도 — 다음 날 잇는다(D4: 한도를 더 올리지 않는다)
 * RATE_LIMIT_EXCEEDED: nginx 분당·초당 제한 — 간격을 늘려 그 회차를 다시 잰다
 */
const LIMIT_CODES: Record<string, string> = { DAILY_REQUEST_LIMIT_EXCEEDED: 'IP_DAILY_LIMIT', RATE_LIMIT_EXCEEDED: 'NGINX_RATE_LIMIT' }
let limitHit: string | null = null
const ipLimitHit = () => limitHit != null
function noteLimit(status: number, code: string | null) {
  // 본문이 없거나 모르는 코드인 429(nginx HTML 등)도 요청 제한이라 멈춘다
  if (status === 429) limitHit ??= (code != null && code in LIMIT_CODES ? LIMIT_CODES[code] : 'HTTP_429')
}
/** 줄을 남기고 0 아닌 코드로 끝낸다 — 운영자가 모르고 다음 회차로 넘어가지 않게 */
function abortRun(remaining: string[]) {
  emit({ stage: STAGE, pass: PASS, position: POSITION, round: ROUND, aborted: limitHit, remaining })
  exec.test.abort(`요청 제한(${limitHit})에 걸려 멈춤 — README "측정일 순서" 참고`)
}

export const options = {
  scenarios: {
    // 한 사람이 차례로 연다. 18권 × (최대 10초 + 2초) ≈ 4분, 더 찾기 끝까지 약 41배치 — 넉넉히 30분
    main: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m' },
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'max', 'count'],
}

/** 화면 api/index.ts coarseCoordinate와 같은 값(소수 셋째 자리) */
function coarse(value: number): number {
  return Math.round(value * 1000) / 1000
}

/** 화면 getNearbyLibraryBatch와 같은 주소 */
function batchUrl(isbn13: string, origin: Origin, offset: number, title: string): string {
  const p: string[] = [`isbn13=${isbn13}`, 'mode=NEARBY', `offset=${offset}`, `title=${encodeURIComponent(title.slice(0, 200))}`]
  if ('referenceLibCode' in origin) p.push(`referenceLibCode=${encodeURIComponent(origin.referenceLibCode)}`)
  else p.push(`lat=${coarse(origin.lat)}`, `lon=${coarse(origin.lon)}`)
  return `${BASE}/api/public-libraries/availability/batch?${p.join('&')}`
}

/** 화면 searchMain과 같은 주소(도서관은 배치가 따로 찾으므로 좌표 없음, 힌트는 제목만) */
function searchUrl(book: Book): string {
  return `${BASE}/api/search?q=${book.isbn}&title=${encodeURIComponent(book.title)}`
}

class HttpFailure extends Error {
  constructor(readonly status: number, readonly code: string | null, readonly k6ErrorCode: number) {
    super(`HTTP ${status} ${code ?? ''}`)
    this.name = 'HttpFailure'
  }
}

/** k6 http 시간 초과는 예외가 아니라 error_code 1050인 응답으로 온다 */
const K6_REQUEST_TIMEOUT = 1050

function bodyCode(res: { body: unknown }): string | null {
  try {
    return (JSON.parse(String(res.body)) as { code?: string }).code ?? null
  } catch {
    return null
  }
}

type BatchLog = { offset: number; startMs: number; ms: number; status: number; items: number; stopReason: string | null }

async function explore(book: Book, origin: Origin, from: ExplorerState, t0: number) {
  const batches: BatchLog[] = []
  let firstFoundMs: number | null = null
  const result: ExploreResult = await runExploreRound(from, {
    now: () => Date.now(),
    isCancelled: () => false,
    onBatch: state => {
      if (firstFoundMs == null && state.found.length > 0) firstFoundMs = Date.now() - t0
    },
    fetchBatch: async (offset, remainingMs) => {
      const start = Date.now()
      const res = await http.asyncRequest('GET', batchUrl(book.isbn, origin, offset, book.title), null, {
        timeout: `${Math.max(1, Math.floor(remainingMs))}ms`,
        tags: { name: 'batch' },
      })
      const log: BatchLog = { offset, startMs: start - t0, ms: Date.now() - start, status: res.status, items: 0, stopReason: null }
      batches.push(log)
      if (res.error_code === K6_REQUEST_TIMEOUT) throw new RoundDeadlineError()
      if (res.status !== 200) {
        const code = bodyCode(res)
        noteLimit(res.status, code)
        throw new HttpFailure(res.status, code, res.error_code)
      }
      const json = res.json() as unknown as { items: unknown[]; stopReason: string }
      log.items = Array.isArray(json.items) ? json.items.length : 0
      log.stopReason = json.stopReason ?? null
      return json as never
    },
  })
  return { result, batches, firstFoundMs }
}

function summarizeExplore(r: Awaited<ReturnType<typeof explore>>, endMs: number) {
  const { result, batches, firstFoundMs } = r
  if (result.cancelled) throw new Error('측정에서는 취소가 없다')
  const { state, stop, error } = result
  const reasons: Record<string, number> = {}
  for (const u of state.unknown) reasons[u.reason ?? 'null'] = (reasons[u.reason ?? 'null'] ?? 0) + 1
  // 첫 표시(규칙 문서 6절): 소장 1곳이 처음 보인 시각, 또는 소장 없음이 확정된 종료(EXHAUSTED·ROUND_LIMIT이면서
  // 확인 못 함 0 — 확인 못 함이 있으면 화면은 "소장이 확인된 곳이 없어요 · N곳 확인 못 함"이라 없음이 확정되지 않았다)
  const noneConfirmed = firstFoundMs == null && (stop === 'EXHAUSTED' || stop === 'ROUND_LIMIT') && state.unknown.length === 0
  return {
    firstDisplayMs: firstFoundMs ?? (noneConfirmed ? endMs : null),
    firstDisplayKind: firstFoundMs != null ? 'found' : noneConfirmed ? 'none' : null,
    settledMs: endMs,
    stop,
    found: state.found.length,
    unknown: state.unknown.length,
    unknownReasons: reasons,
    checked: state.seen.length,
    nextOffset: state.nextOffset,
    batches,
    error: error == null ? null : error instanceof HttpFailure
      ? { status: error.status, code: error.code, k6ErrorCode: error.k6ErrorCode }
      : { message: String((error as Error)?.message ?? error) },
    state,
  }
}

async function search(book: Book, t0: number) {
  const res = await http.asyncRequest('GET', searchUrl(book), null, { timeout: '30s', tags: { name: 'search' } })
  const out: Record<string, unknown> = { ms: Date.now() - t0, status: res.status }
  if (res.status === 200) {
    const json = res.json() as unknown as { metadata?: { sectionStatuses?: Array<Record<string, unknown>> } }
    out.sections = json.metadata?.sectionStatuses ?? null
  } else {
    out.code = bodyCode(res)
    noteLimit(res.status, out.code as string | null)
  }
  return out
}

function emit(record: Record<string, unknown>) {
  // --console-output 파일에 한 줄씩 남는다. 다른 로그와 섞이지 않게 머리표를 붙인다
  console.log(`MEASURE ${JSON.stringify(record)}`)
}

function base(book: Book, t0: number) {
  return { stage: STAGE, pass: PASS, position: POSITION, round: ROUND, isbn: book.isbn, bin: book.bin, startedAt: new Date(t0).toISOString() }
}

/** 상세 화면 하나를 연 것: 검색과 탐색을 함께 보내고 둘 다 끝나면 한 줄 */
async function openBook(book: Book, origin: Origin) {
  const t0 = Date.now()
  const searchP = search(book, t0)
  const ex = await explore(book, origin, INITIAL_STATE, t0)
  const endMs = Date.now() - t0
  const s = await searchP
  const { state: _state, ...lib } = summarizeExplore(ex, endMs)
  emit({ ...base(book, t0), library: lib, search: s })
}

/** 더 찾기를 끝까지: 화면의 canMore(nextOffset 남음, DAILY_LIMIT 아님, 400 아님)가 거짓이 될 때까지 */
async function openBookAndMoreToEnd(book: Book, origin: Origin) {
  const t0 = Date.now()
  const searchP = search(book, t0)
  let state: ExplorerState = INITIAL_STATE
  const rounds: Record<string, unknown>[] = []
  while (true) {
    const roundStart = Date.now()
    const ex = await explore(book, origin, state, roundStart)
    const sum = summarizeExplore(ex, Date.now() - roundStart)
    state = sum.state
    const { state: _s, ...rest } = sum
    rounds.push(rest)
    const fatal = sum.error != null && (sum.error as { status?: number }).status === 400
    if (state.nextOffset == null || sum.stop === 'DAILY_LIMIT' || fatal) break
    // 화면의 canMore는 ERROR·CIRCUIT_OPEN 뒤에도 더 찾기를 열어 두지만, 사람이 장애 중에 끝없이 누르지는 않는다
    if (sum.stop === 'ERROR' || sum.stop === 'CIRCUIT_OPEN' || ipLimitHit() || rounds.length >= MORE_ROUND_LIMIT) break
    sleep(MORE_CLICK_SECONDS)
  }
  emit({ ...base(book, t0), rounds, totalChecked: state.seen.length, totalFound: state.found.length, search: await searchP })
  if (ipLimitHit()) abortRun([])
}

function pick(listKey: string, bin: string): Book {
  const book = (plan.books[listKey] as Book[]).find(b => b.bin === bin)
  if (!book) throw new Error(`책 없음: ${listKey}/${bin}`)
  return book
}

export default async function () {
  if (PASS === 'smoke') {
    const isbn = __ENV.ISBN || '9791161571188'
    const listed = Object.values(plan.books as Record<string, Book[]>).some(list => list.some(b => b.isbn === isbn))
    if (listed) throw new Error('smoke는 측정 목록 밖의 책으로만 연다(측정 캐시를 채우지 않게)')
    await openBook({ isbn, bin: 'smoke', title: '불편한 편의점' }, { lat: position.lat, lon: position.lon })
    if (ipLimitHit()) abortRun([])
    return
  }
  if (PASS === 'more') {
    const x = plan.extras.more
    await openBookAndMoreToEnd(pick(x.books, x.bin), { lat: position.lat, lon: position.lon })
    return
  }
  if (PASS === 'reference') {
    const x = plan.extras.reference
    await openBook(pick(x.books, x.bin), { referenceLibCode: x.referenceLibCode })
    if (ipLimitHit()) abortRun([])
    return
  }
  const books = plan.books[position.books] as Book[]
  for (let i = 0; i < books.length; i++) {
    if (ipLimitHit()) {
      abortRun(books.slice(i).map(b => b.isbn))
      return
    }
    if (i > 0) sleep(GAP_SECONDS)
    await openBook(books[i], { lat: position.lat, lon: position.lon })
  }
  if (ipLimitHit()) abortRun([])
}
