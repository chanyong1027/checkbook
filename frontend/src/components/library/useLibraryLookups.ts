import { useCallback, useEffect, useRef, useState } from 'react'
import { getNearbyLibraryBatch, getSavedLibraryBatch } from '../../api'
import { ApiError } from '../../api/errors.ts'
import type { PublicLibraryBatchItem } from '../../types'
import {
  INITIAL_STATE,
  NEW_ROUND,
  ROUND_DEADLINE_MS,
  applyBatch,
  hasTimeForNextBatch,
  mergeRetry,
  type ExploreStop,
  type ExplorerState,
  type RoundState,
} from './nearbyExplorer.ts'

export type LibraryOrigin = { lat: number; lon: number } | { referenceLibCode: string }

// 하루 한도(앱 429)·공급자 예산(앱 503)·속도 제한(Nginx 429 JSON)은 서버 문구를 그대로 보인다
const LIMIT_ERROR_CODES = new Set(['DAILY_REQUEST_LIMIT_EXCEEDED', 'PROVIDER_DAILY_LIMIT', 'RATE_LIMIT_EXCEEDED'])

export function lookupErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.code === 'NON_STANDARD_ISBN') {
    return '표준 ISBN이 없는 상품이라 도서관 소장 여부를 확인할 수 없어요'
  }
  if (err instanceof ApiError && err.code != null && LIMIT_ERROR_CODES.has(err.code)) return err.message
  if (err instanceof ApiError && err.code === 'UNKNOWN_PUBLIC_LIBRARY') {
    return '찾을 수 없는 도서관이 있어요. 내 도서관 설정에서 다시 골라 주세요'
  }
  return fallback
}

function originKey(origin: LibraryOrigin | null): string {
  if (!origin) return ''
  return 'referenceLibCode' in origin ? `ref:${origin.referenceLibCode}` : `gps:${origin.lat},${origin.lon}`
}

function isAbort(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === 'AbortError'
}

/**
 * 주변 탐색. 기준(origin)·책이 바뀌면 처음부터 다시 찾고, 지난 응답은 세대 번호로 버린다.
 * 한 탐색 동안 같은 기준을 보낸다(GPS를 다시 받으면 순서가 바뀌어 offset이 어긋남).
 */
export function useNearbyExplorer(isbn13: string, origin: LibraryOrigin | null, enabled: boolean) {
  const [state, setState] = useState<ExplorerState>(INITIAL_STATE)
  const [running, setRunning] = useState(false)
  const [stop, setStop] = useState<ExploreStop | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  const generation = useRef(0)
  const abortRef = useRef<AbortController | null>(null)
  const stateRef = useRef(state)
  stateRef.current = state
  const key = originKey(origin)
  const originRef = useRef(origin)
  originRef.current = origin

  const explore = useCallback(async (from: ExplorerState) => {
    const current = originRef.current
    if (!current) return
    const gen = ++generation.current
    abortRef.current?.abort()
    setRunning(true)
    setStop(null)
    setError(null)
    const started = performance.now()
    let s = from
    let round: RoundState = NEW_ROUND
    let finalStop: ExploreStop = 'EXHAUSTED'
    while (true) {
      const offset = s.nextOffset
      if (offset == null) { finalStop = 'EXHAUSTED'; break }
      const elapsed = performance.now() - started
      if (round.checked > 0 && !hasTimeForNextBatch(elapsed)) { finalStop = 'TIME'; break }
      const controller = new AbortController()
      abortRef.current = controller
      // 서버 배치 마감(2.2초)과 별개로, 네트워크가 멈춰도 탐색 전체 10초를 넘기지 않게 끊는다
      let timedOut = false
      const timer = setTimeout(() => { timedOut = true; controller.abort() }, Math.max(0, ROUND_DEADLINE_MS - elapsed))
      try {
        const res = await getNearbyLibraryBatch(isbn13, current, offset, controller.signal)
        if (gen !== generation.current) return
        const result = applyBatch(s, round, offset, res)
        s = result.state
        round = result.round
        setState(s)
        if (result.stop) { finalStop = result.stop; break }
      } catch (err) {
        if (gen !== generation.current) return
        if (timedOut) { finalStop = 'TIME'; break }
        if (isAbort(err)) return
        // 응답을 못 받은 구간은 넘어가지 않는다 — 진행 위치를 그대로 두고 더 찾기로 같은 곳부터 다시
        finalStop = 'ERROR'
        setError(lookupErrorMessage(err, '도서관을 더 찾지 못했어요. 잠시 뒤 다시 시도해 주세요'))
        break
      } finally {
        clearTimeout(timer)
      }
    }
    if (gen !== generation.current) return
    setRunning(false)
    setStop(finalStop)
  }, [isbn13])

  // 책·기준이 바뀌면 새로 시작(자동 탐색)
  useEffect(() => {
    generation.current++
    abortRef.current?.abort()
    setState(INITIAL_STATE)
    setStop(null)
    setError(null)
    setRunning(false)
    if (enabled && originRef.current) explore(INITIAL_STATE)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isbn13, key, enabled])

  useEffect(() => () => {
    generation.current++
    abortRef.current?.abort()
  }, [])

  const stopNow = useCallback(() => {
    generation.current++
    abortRef.current?.abort()
    setRunning(false)
    setStop('USER')
  }, [])

  const more = useCallback(() => explore(stateRef.current), [explore])

  /** 확인 못 한 곳을 5곳까지 다시 확인한다. 진행 위치·거리는 지킨다 */
  const retryUnknown = useCallback(async () => {
    const codes = stateRef.current.unknown.slice(0, 5).map(u => u.libCode)
    if (codes.length === 0) return
    const gen = generation.current
    setRetrying(true)
    setError(null)
    try {
      const res = await getSavedLibraryBatch(isbn13, codes)
      if (gen !== generation.current) return
      setState(prev => mergeRetry(prev, res))
    } catch (err) {
      if (gen !== generation.current) return
      setError(lookupErrorMessage(err, '다시 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요'))
    } finally {
      if (gen === generation.current) setRetrying(false)
    }
  }, [isbn13])

  return { state, running, stop, error, retrying, more, stopNow, retryUnknown }
}

/** 내 도서관(저장한 기관 최대 5곳). 한 배치로 끝나고, 미소장도 그대로 보여 준다 */
export function useSavedLibraries(isbn13: string, libCodes: string[], enabled: boolean) {
  const [items, setItems] = useState<PublicLibraryBatchItem[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const codesKey = libCodes.join(',')

  const load = useCallback(() => {
    abortRef.current?.abort()
    if (!codesKey) {
      setItems(null)
      return
    }
    const controller = new AbortController()
    abortRef.current = controller
    setLoading(true)
    setError(null)
    getSavedLibraryBatch(isbn13, codesKey.split(','), controller.signal)
      .then(res => {
        if (abortRef.current === controller) setItems(res.items)
      })
      .catch(err => {
        if (!isAbort(err) && abortRef.current === controller) {
          setError(lookupErrorMessage(err, '내 도서관을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요'))
        }
      })
      .finally(() => {
        if (abortRef.current === controller) setLoading(false)
      })
  }, [isbn13, codesKey])

  useEffect(() => {
    setItems(null)
    if (enabled) load()
    return () => abortRef.current?.abort()
  }, [enabled, load])

  return { items, loading, error, reload: load }
}
