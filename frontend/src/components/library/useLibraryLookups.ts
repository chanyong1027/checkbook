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
export function useNearbyExplorer(isbn13: string, origin: LibraryOrigin | null, enabled: boolean, title?: string | null) {
  // 제목은 의존값이 아니라 ref로 읽는다 — 공유 링크 진입처럼 제목이 늦게 오면 그 뒤 배치부터 링크가 붙고, 탐색은 다시 시작하지 않는다
  const titleRef = useRef(title)
  useEffect(() => { titleRef.current = title }, [title])
  const [state, setState] = useState<ExplorerState>(INITIAL_STATE)
  const [running, setRunning] = useState(false)
  const [stop, setStop] = useState<ExploreStop | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  /** 400처럼 다시 해도 같은 결과인 오류 — 더 찾기를 감춘다 */
  const [fatal, setFatal] = useState(false)
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
    setFatal(false)
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
        const res = await getNearbyLibraryBatch(isbn13, current, offset, controller.signal, titleRef.current)
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
        setFatal(err instanceof ApiError && err.status === 400)
        break
      } finally {
        clearTimeout(timer)
      }
    }
    if (gen !== generation.current) return
    setRunning(false)
    setStop(finalStop)
  }, [isbn13])

  const runningRef = useRef(running)
  runningRef.current = running
  const stopRef = useRef(stop)
  stopRef.current = stop
  // 마지막으로 탐색을 시작한 책·기준. 모드를 오가도(enabled 꺼짐→켜짐) 같은 기준이면 결과를 지우지 않고 이어 본다
  const startedFor = useRef<string | null>(null)

  useEffect(() => {
    const target = `${isbn13}|${key}`
    if (!enabled) {
      // 다른 모드로 바꾸면 멈추기만 한다(하루 IP 한도를 아끼려고 결과는 남긴다)
      if (runningRef.current) {
        generation.current++
        abortRef.current?.abort()
        setRunning(false)
        setStop('USER')
      }
      return
    }
    // 같은 책·기준이면 다시 시작하지 않는다. 다만 아무것도 확인하기 전에 모드 전환으로 끊겼으면 다시 찾는다
    // (오류·한도로 멈춘 경우는 모드를 오가도 자동으로 다시 부르지 않음)
    const interruptedEmpty = stateRef.current.seen.length === 0 && stopRef.current === 'USER'
    if (startedFor.current === target && !interruptedEmpty) return
    startedFor.current = target
    generation.current++
    abortRef.current?.abort()
    setState(INITIAL_STATE)
    setStop(null)
    setError(null)
    setFatal(false)
    setRetrying(false)
    setRunning(false)
    if (originRef.current) explore(INITIAL_STATE)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isbn13, key, enabled])

  useEffect(() => () => {
    generation.current++
    abortRef.current?.abort()
    startedFor.current = null // Strict Mode의 가짜 언마운트 뒤 다시 붙을 때 새로 시작하게
  }, [])

  const stopNow = useCallback(() => {
    generation.current++
    abortRef.current?.abort()
    setRunning(false)
    setStop('USER')
  }, [])

  const more = useCallback(() => explore(stateRef.current), [explore])

  /** 확인 못 한 곳을 5곳까지 다시 확인한다. 진행 위치·거리는 지킨다 */
  const retrySeq = useRef(0)
  const retryUnknown = useCallback(async () => {
    const codes = stateRef.current.unknown.slice(0, 5).map(u => u.libCode)
    if (codes.length === 0) return
    const gen = generation.current
    const seq = ++retrySeq.current
    setRetrying(true)
    setError(null)
    try {
      const res = await getSavedLibraryBatch(isbn13, codes, undefined, titleRef.current)
      if (gen !== generation.current) return
      setState(prev => mergeRetry(prev, res))
    } catch (err) {
      if (gen !== generation.current) return
      setError(lookupErrorMessage(err, '다시 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요'))
    } finally {
      // 그사이 탐색이 새로 시작됐어도 버튼은 풀어 준다(결과만 버림). 더 나중 다시 확인이 진행 중이면 그쪽이 푼다
      if (seq === retrySeq.current) setRetrying(false)
    }
  }, [isbn13])

  return { state, running, stop, error, fatal, retrying, more, stopNow, retryUnknown }
}

/** 내 도서관(저장한 기관 최대 5곳). 한 배치로 끝나고, 미소장도 그대로 보여 준다 */
export function useSavedLibraries(isbn13: string, libCodes: string[], enabled: boolean, title?: string | null) {
  const titleRef = useRef(title)
  useEffect(() => { titleRef.current = title }, [title])
  const [items, setItems] = useState<PublicLibraryBatchItem[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const codesKey = libCodes.join(',')
  const itemsRef = useRef(items)
  useEffect(() => { itemsRef.current = items }, [items])
  /** 첫 조회 중에 제목이 왔다 — 첫 조회가 끝나 목록이 보이면 조용히 한 번 더 불러 링크를 붙인다 */
  const linkAfterFirstRef = useRef(false)

  /** quiet: 이미 보이는 목록에 사이트 링크만 붙이려는 재조회 — 불러오는 중·오류 표시를 건드리지 않고 성공할 때만 바꾼다 */
  const load = useCallback((wantQuiet = false) => {
    // 첫 조회가 끝나기 전에 제목이 오면(공유 링크 진입) 첫 조회를 버리지 않는다. 버리고 새로 부르면
    // '확인 중'이 길어지고 배치 한 번을 낭비한다(11-04 사용자 확인). 목록이 뜬 뒤 조용히 부른다
    if (wantQuiet && itemsRef.current == null && abortRef.current) {
      linkAfterFirstRef.current = true
      return
    }
    // 목록이 없을 때 조용히 부르면 실패해도 오류가 안 떠 '확인 중'에서 멈춘다(11-04 리뷰) — 목록이 있을 때만 조용히
    const quiet = wantQuiet && itemsRef.current != null
    linkAfterFirstRef.current = false
    abortRef.current?.abort()
    if (!codesKey) {
      setItems(null)
      return
    }
    const controller = new AbortController()
    abortRef.current = controller
    if (!quiet) {
      setLoading(true)
      setError(null)
    }
    getSavedLibraryBatch(isbn13, codesKey.split(','), controller.signal, titleRef.current)
      .then(res => {
        if (abortRef.current === controller) {
          setItems(res.items)
          itemsRef.current = res.items // 아래 이어 부르기가 '목록 있음'으로 보게 바로 맞춘다
        }
      })
      .catch(err => {
        if (!isAbort(err) && abortRef.current === controller && !quiet) {
          setError(lookupErrorMessage(err, '내 도서관을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요'))
        }
      })
      .finally(() => {
        if (abortRef.current === controller) {
          setLoading(false)
          abortRef.current = null // 끝난 요청 — 아래에서 '불러오는 중'으로 보지 않게
          if (linkAfterFirstRef.current) {
            linkAfterFirstRef.current = false
            // 첫 조회가 실패했으면 목록이 없어 오류·다시 시도가 보인다. 다시 시도는 제목을 싣고 부른다
            if (itemsRef.current != null) load(true)
          }
        }
      })
  }, [isbn13, codesKey])

  // 모드를 오가도 같은 목록이면 다시 부르지 않는다. 목록이 바뀌었거나 아직 결과가 없을 때만
  const loadedFor = useRef<string | null>(null)
  const hasTitle = Boolean(title)
  useEffect(() => {
    if (!enabled) {
      // 불러오던 중에 바꿨으면 결과가 없으니, 돌아올 때 다시 부르게 표시를 지운다
      if (abortRef.current) loadedFor.current = null
      abortRef.current?.abort()
      setLoading(false)
      return
    }
    // 제목이 늦게 오면(공유 링크 진입) 한 번 더 불러 사이트 검색 링크를 붙인다. 이때는 목록을 비우지 않는다(깜빡임 방지)
    const key = `${isbn13}|${codesKey}|${hasTitle}`
    if (loadedFor.current === key) return
    const sameList = loadedFor.current?.startsWith(`${isbn13}|${codesKey}|`) ?? false
    loadedFor.current = key
    if (!sameList) setItems(null)
    load(sameList)
  }, [enabled, load, isbn13, codesKey, hasTitle])

  useEffect(() => () => {
    abortRef.current?.abort()
    loadedFor.current = null
  }, [])

  return { items, loading, error, reload: () => load(false) }
}
