/**
 * 주변 도서관 탐색(D7-1 결정 카드 T1·T2·T4). 화면이 5곳씩 배치를 이어 불러 "소장한 가까운 5곳"을 채운다.
 * 네트워크·시간과 떼어 둔 순수 함수라 node --test로 시험한다(호출 반복은 useNearbyExplorer).
 *
 * 한 번의 탐색(처음 찾기·더 찾기 각각)은 아래 중 먼저 오는 것에서 멈춘다:
 * 소장 5곳 / 후보 20곳 확인 / 10초 / 후보 소진(반경 10km) / 서버 중단 사유 / 배치 전부 확인 못 함 / 사용자 중지
 */
import type { BatchStopReason, PublicLibraryBatchItem, PublicLibraryBatchResponse } from '../../types'

export const ROUND_GOAL = 5
export const ROUND_CANDIDATE_LIMIT = 20
export const ROUND_DEADLINE_MS = 10_000
/** 남은 시간이 이보다 적으면 다음 배치를 보내지 않는다(배치 마감 2.2초 + 응답 여유) */
export const MIN_REMAINING_MS = 2_400

export type ExploreStop =
  | 'GOAL'
  | 'ROUND_LIMIT'
  | 'TIME'
  | 'EXHAUSTED'
  | 'ALL_UNKNOWN'
  | 'USER'
  | 'ERROR'
  | Exclude<BatchStopReason, 'NONE'>

export interface ExplorerState {
  /** 보여 줄 소장 기관(대출가능·대출중), 거리순 */
  found: PublicLibraryBatchItem[]
  /** 확인 못 한 기관. 미소장으로 숨기지 않고 따로 보여 준다 */
  unknown: PublicLibraryBatchItem[]
  /** 판정한 기관 코드(소장·미소장·확인 못 함 모두). "N곳 확인"과 중복 방지에 쓴다 */
  seen: string[]
  /** 다음에 이어 찾을 후보 위치. null이면 반경 안 후보를 다 봤다 */
  nextOffset: number | null
}

export interface RoundState {
  found: number
  checked: number
}

export const INITIAL_STATE: ExplorerState = { found: [], unknown: [], seen: [], nextOffset: 0 }
export const NEW_ROUND: RoundState = { found: 0, checked: 0 }

const isHeld = (item: PublicLibraryBatchItem) => item.state === 'AVAILABLE' || item.state === 'ON_LOAN'

function byDistance(a: PublicLibraryBatchItem, b: PublicLibraryBatchItem): number {
  return (a.distanceKm ?? Number.POSITIVE_INFINITY) - (b.distanceKm ?? Number.POSITIVE_INFINITY)
}

/**
 * 배치 하나를 반영한다. offset은 이 배치를 요청한 위치.
 * 목표(5곳)를 넘친 소장 기관은 버리고, 다음 탐색은 버린 첫 기관 위치부터 다시 찾는다(그 구간은 서버 스냅샷에 있어
 * 외부 호출 없이 온다). 그 뒤의 기관도 다시 오므로 여기서는 세지 않는다.
 */
export function applyBatch(
  state: ExplorerState,
  round: RoundState,
  offset: number,
  res: PublicLibraryBatchResponse,
): { state: ExplorerState; round: RoundState; stop: ExploreStop | null } {
  const found = [...state.found]
  let unknown = [...state.unknown]
  const seen = new Set(state.seen)
  let roundFound = round.found
  let roundChecked = round.checked
  let overflowAt: number | null = null

  for (let i = 0; i < res.items.length; i++) {
    const item = res.items[i]
    if (found.some(f => f.libCode === item.libCode)) continue
    if (isHeld(item)) {
      if (roundFound >= ROUND_GOAL) {
        overflowAt = offset + i
        break
      }
      unknown = unknown.filter(u => u.libCode !== item.libCode)
      found.push(item)
      roundFound++
    } else if (item.state === 'UNKNOWN') {
      if (!unknown.some(u => u.libCode === item.libCode)) unknown.push(item)
    } else {
      unknown = unknown.filter(u => u.libCode !== item.libCode)
    }
    seen.add(item.libCode)
    roundChecked++
  }

  found.sort(byDistance)
  const next: ExplorerState = {
    found,
    unknown,
    seen: [...seen],
    nextOffset: overflowAt ?? res.nextOffset,
  }
  const nextRound = { found: roundFound, checked: roundChecked }

  let stop: ExploreStop | null = null
  const serverStop = res.stopReason && res.stopReason !== 'NONE' ? res.stopReason as ExploreStop : null
  // 예산 소진·서킷 열림은 정보나루 전체가 막힌 것이라 목표를 채웠어도 먼저 알린다 — 더 찾기를 눌러도 헛걸음이라.
  // 지연·혼잡(BATCH_DEADLINE·POOL_BUSY)은 다음에 풀릴 수 있어 목표 달성이 우선이다
  if (serverStop === 'DAILY_LIMIT' || serverStop === 'CIRCUIT_OPEN') stop = serverStop
  else if (overflowAt != null || roundFound >= ROUND_GOAL) stop = 'GOAL'
  else if (serverStop) stop = serverStop
  else if (res.items.length > 0 && res.items.every(item => item.state === 'UNKNOWN')) stop = 'ALL_UNKNOWN'
  else if (next.nextOffset == null) stop = 'EXHAUSTED'
  else if (roundChecked >= ROUND_CANDIDATE_LIMIT) stop = 'ROUND_LIMIT'
  return { state: next, round: nextRound, stop }
}

/** 다음 배치를 보낼 시간이 남았는지(탐색 시작부터 잰 경과 ms) */
export function hasTimeForNextBatch(elapsedMs: number): boolean {
  return ROUND_DEADLINE_MS - elapsedMs >= MIN_REMAINING_MS
}

/**
 * 확인 못 한 곳을 다시 확인한 결과(SAVED 모드라 거리·위치 정보가 없다)를 합친다.
 * 거리와 진행 위치는 원래 값을 지킨다. 복구된 소장 기관은 5곳을 넘어도 숨기지 않는다.
 */
export function mergeRetry(state: ExplorerState, res: PublicLibraryBatchResponse): ExplorerState {
  const found = [...state.found]
  let unknown = [...state.unknown]
  for (const item of res.items) {
    const original = unknown.find(u => u.libCode === item.libCode)
    if (!original) continue
    if (isHeld(item)) {
      unknown = unknown.filter(u => u.libCode !== item.libCode)
      found.push({ ...original, state: item.state, reason: null })
    } else if (item.state === 'NOT_HELD') {
      unknown = unknown.filter(u => u.libCode !== item.libCode)
    } else {
      unknown = unknown.map(u => (u.libCode === item.libCode ? { ...u, reason: item.reason } : u))
    }
  }
  found.sort(byDistance)
  return { ...state, found, unknown }
}

/** 탐색이 왜 멈췄는지 사용자에게 보일 한 줄. 목표 달성·사용자 중지는 따로 말하지 않는다 */
export function stopMessage(stop: ExploreStop | null): string | null {
  switch (stop) {
    case null:
    case 'GOAL':
      return null
    case 'USER': return '찾기를 멈췄어요'
    case 'EXHAUSTED': return '10km 안 도서관을 모두 확인했어요'
    case 'ROUND_LIMIT': return null
    case 'DAILY_LIMIT': return '오늘은 도서관 조회가 많아 더 찾을 수 없어요. 내일 다시 시도해 주세요'
    case 'CIRCUIT_OPEN': return '도서관 정보 제공처가 응답하지 않아 잠시 멈췄어요. 조금 뒤 더 찾아 주세요'
    case 'ERROR': return '도서관을 더 찾지 못했어요. 잠시 뒤 다시 시도해 주세요'
    default: return '응답이 늦어 여기까지 찾았어요'
  }
}
