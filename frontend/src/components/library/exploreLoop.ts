/**
 * 주변 탐색 한 번(처음 찾기·더 찾기 각각)의 반복 고리. 배치 부르기·시계·취소 확인을 주입받는다.
 * 화면 훅(useNearbyExplorer)과 측정 스크립트(scripts/measure, k6)가 같은 고리를 쓰게 하려고 훅에서 뺐다
 * (2026-11-18 결정 카드 D5). 그래서 브라우저 전용 API(AbortController·setTimeout·performance)를 여기서 쓰지 않는다.
 */
import type { PublicLibraryBatchResponse } from '../../types'
import {
  NEW_ROUND,
  ROUND_DEADLINE_MS,
  applyBatch,
  hasTimeForNextBatch,
  type ExploreStop,
  type ExplorerState,
  type RoundState,
} from './nearbyExplorer.ts'

/** fetchBatch가 바깥 마감(탐색 전체 10초)으로 요청을 끊었을 때 던진다 */
export class RoundDeadlineError extends Error {
  constructor() {
    super('explore round deadline')
    this.name = 'RoundDeadlineError'
  }
}

export interface ExploreDeps {
  /** offset부터 배치 하나. remainingMs 안에 못 받으면 끊고 RoundDeadlineError를 던진다 */
  fetchBatch(offset: number, remainingMs: number): Promise<PublicLibraryBatchResponse>
  /** 단조 증가 ms 시계 */
  now(): number
  /** 이 탐색이 더는 유효하지 않은지(새 탐색 시작·사용자 중지·언마운트) */
  isCancelled(): boolean
  /** 배치 하나를 반영할 때마다(화면은 그리고, 측정은 첫 표시 시각을 적는다) */
  onBatch?(state: ExplorerState): void
}

export type ExploreResult =
  | { cancelled: true }
  | { cancelled: false; state: ExplorerState; stop: ExploreStop; error: unknown }

const CANCELLED: ExploreResult = { cancelled: true }

function isAbort(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === 'AbortError'
}

export async function runExploreRound(from: ExplorerState, deps: ExploreDeps): Promise<ExploreResult> {
  const started = deps.now()
  let state = from
  let round: RoundState = NEW_ROUND
  const done = (stop: ExploreStop, error: unknown = null): ExploreResult => ({ cancelled: false, state, stop, error })
  while (true) {
    const offset = state.nextOffset
    if (offset == null) return done('EXHAUSTED')
    const elapsed = deps.now() - started
    if (round.checked > 0 && !hasTimeForNextBatch(elapsed)) return done('TIME')
    let res: PublicLibraryBatchResponse
    try {
      res = await deps.fetchBatch(offset, Math.max(0, ROUND_DEADLINE_MS - elapsed))
    } catch (err) {
      if (deps.isCancelled()) return CANCELLED
      if (err instanceof RoundDeadlineError) return done('TIME')
      if (isAbort(err)) return CANCELLED
      // 응답을 못 받은 구간은 넘어가지 않는다 — 진행 위치를 그대로 두고 더 찾기로 같은 곳부터 다시
      return done('ERROR', err)
    }
    if (deps.isCancelled()) return CANCELLED
    let stop: ExploreStop | null
    try {
      // 200인데 형태가 다른 응답(items 없음)처럼 반영하다 터져도 ERROR로 끝낸다 — 밖으로 던지면 화면이 '찾는 중'에 멈춘다
      const result = applyBatch(state, round, offset, res)
      state = result.state
      round = result.round
      stop = result.stop
      deps.onBatch?.(state)
    } catch (err) {
      return done('ERROR', err)
    }
    if (stop) return done(stop)
  }
}
