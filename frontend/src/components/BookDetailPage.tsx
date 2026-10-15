import { useState, useEffect, useMemo, useRef } from 'react'
import { searchBooks, searchMain, searchELibraries, getELibraries } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type {
  BookCandidate,
  MillieAvailability,
  SearchResponse,
  SearchSectionStatus,
  ELibraryInfo,
  ELibrarySearchResponse,
} from '../types'
import { OffStoreList } from './OffStoreList'
import { PublicLibraryList } from './PublicLibraryList'
import { BookDetailCard } from './shared/BookDetailCard'
import { SectionCard } from './shared/SectionCard'
import { Skeleton } from './shared/Skeleton'
import { BottomSheet } from './shared/BottomSheet'
import { ConfirmDialog } from './shared/ConfirmDialog'
import { isSafeUrl } from '../utils/url'

interface Props {
  isbn13: string
  initialBook?: BookCandidate | null
  onReset: () => void
}

// --- E-library saved IDs helpers ---

const ELIB_SELECTION_KEY = 'checkbook_elib_ids'
const MAX_SELECT = 20

function getSavedIds(): number[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ELIB_SELECTION_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((id): id is number => Number.isInteger(id) && id > 0)
      .slice(0, MAX_SELECT)
  } catch {
    return []
  }
}

function saveIds(ids: number[]) {
  try {
    localStorage.setItem(ELIB_SELECTION_KEY, JSON.stringify(ids))
  } catch {
    // 저장소 접근 불가해도 현재 선택/재검색 흐름은 계속 진행
  }
}

// --- Status helpers ---

const elibStatusColor: Record<string, string> = {
  SUCCESS: 'text-emerald-600',
  FAILED: 'text-red-500',
  TIMEOUT: 'text-orange-500',
}

const elibStatusLabel: Record<string, string> = {
  SUCCESS: '완료',
  FAILED: '실패',
  TIMEOUT: '타임아웃',
}

/** 공유 링크 첫 진입 때 카카오 후보 조회를 기다리는 최대 시간. 넘기면 힌트 없이 통합 검색을 시작한다 */
const COLD_FETCH_TIMEOUT_MS = 3000

/**
 * 새책 칸 안내 문구. 가격을 보여 줄 수 없는 경우만 문구를 돌려준다(null이면 가격 행 또는 숨김).
 * 모르는 사유는 status로 처리한다.
 */
function newBookNoticeText(status: SearchSectionStatus, reason?: string | null): string | null {
  switch (reason) {
    case 'NON_STANDARD_ISBN': return '표준 ISBN이 없는 상품이라 새책 가격을 확인할 수 없어요'
    case 'NOT_FOUND': return 'YES24에 판매 정보가 없어요'
    case 'PRICE_UNAVAILABLE': return 'YES24에 가격 정보가 없어요'
    case 'RATE_LIMITED': return '요청이 많아 가격을 확인하지 못했어요. 잠시 후 다시 시도해주세요'
    case 'DAILY_LIMIT': return '오늘은 가격 확인이 어려워요. 내일 다시 시도해주세요'
  }
  return status === 'FAILED' ? '가격 정보를 가져올 수 없습니다' : null
}

function formatLabel(format: MillieAvailability['format']): string {
  switch (format) {
    case 'EBOOK': return '전자책'
    case 'AUDIOBOOK': return '오디오북'
    case 'EBOOK_AND_AUDIOBOOK': return '전자책+오디오북'
    default: return '구독 가능'
  }
}

// --- Loading skeletons ---

function SearchLoadingSkeleton() {
  return (
    <div className="space-y-3 animate-pulse">
      <div className="bg-white rounded-2xl border border-orange-50 p-4">
        <Skeleton className="h-4 w-28 mb-3" />
        <div className="space-y-2.5">
          {[1, 2, 3].map(i => (
            <div key={i} className="flex items-center justify-between">
              <Skeleton className="h-3.5 w-24" />
              <Skeleton className="h-5 w-10 rounded-full" />
            </div>
          ))}
        </div>
      </div>
      <div className="bg-white rounded-2xl border border-orange-50 p-4">
        <Skeleton className="h-4 w-20 mb-3" />
        <div className="space-y-2.5">
          {[1, 2].map(i => (
            <div key={i} className="flex justify-between">
              <Skeleton className="h-3.5 w-16" />
              <Skeleton className="h-3.5 w-14" />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function ElibLoadingSkeleton() {
  return (
    <div className="animate-pulse bg-white rounded-2xl border border-orange-50 p-4">
      <Skeleton className="h-4 w-24 mb-3" />
      <div className="space-y-2.5">
        {[1, 2].map(i => (
          <div key={i} className="flex items-center justify-between">
            <Skeleton className="h-3.5 w-32" />
            <Skeleton className="h-5 w-10 rounded-full" />
          </div>
        ))}
      </div>
    </div>
  )
}

// --- E-library selector bottom sheet content ---

function setsEqual<T>(a: Set<T>, b: Set<T>): boolean {
  if (a.size !== b.size) return false
  for (const v of a) if (!b.has(v)) return false
  return true
}

function ELibrarySelector({
  initialIds,
  onConfirm,
  onCancel,
  onDirtyChange,
}: {
  initialIds: Set<number>
  onConfirm: (ids: number[]) => void
  onCancel: () => void
  onDirtyChange?: (dirty: boolean) => void
}) {
  const [libraries, setLibraries] = useState<ELibraryInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [draftIds, setDraftIds] = useState<Set<number>>(() => new Set(initialIds))
  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    getELibraries()
      .then(setLibraries)
      .catch(e => setError(toUserMessage(e, '전자도서관 목록을 불러오지 못했습니다.')))
      .finally(() => setLoading(false))
  }, [])

  // 변경 여부 추적 — 백드롭/취소 시 부모에서 확인 다이얼로그 게이트로 사용
  useEffect(() => {
    onDirtyChange?.(!setsEqual(draftIds, initialIds))
  }, [draftIds, initialIds, onDirtyChange])

  function toggle(id: number) {
    setDraftIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else if (next.size < MAX_SELECT) next.add(id)
      return next
    })
  }

  function clearAll() {
    setDraftIds(new Set())
  }

  const filteredLibs = [...(keyword
    ? libraries.filter(l =>
        l.name.includes(keyword) || l.region?.includes(keyword) || l.vendorType?.includes(keyword)
      )
    : libraries
  )].sort((a, b) => a.name.localeCompare(b.name, 'ko'))

  const overLimit = draftIds.size > MAX_SELECT
  const selectedLibs = libraries.filter(l => draftIds.has(l.libraryId))

  if (loading) {
    return (
      <div className="flex justify-center py-12">
        <svg className="animate-spin text-primary" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
          <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
        </svg>
      </div>
    )
  }

  if (error) {
    return <div className="p-3 bg-red-50 border border-red-100 rounded-2xl text-red-500 text-sm">{error}</div>
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-center justify-between mb-2">
        <p className="text-[13px] font-medium text-slate-600">
          도서관 선택
          <span className={`ml-1.5 ${overLimit ? 'text-red-500' : 'text-slate-500'}`}>
            {draftIds.size}/{MAX_SELECT}
          </span>
        </p>
        {draftIds.size > 0 && (
          <button onClick={clearAll} className="px-2 py-1 -mr-1 text-xs text-primary hover:underline cursor-pointer">
            전체 해제
          </button>
        )}
      </div>

      {/* Selected chips */}
      {selectedLibs.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mb-3">
          {selectedLibs.map(lib => (
            <span
              key={lib.libraryId}
              className="inline-flex items-center gap-1 pl-2.5 pr-1 py-1 rounded-full
                bg-orange-50 border border-orange-200 text-xs text-orange-700 font-medium"
            >
              {lib.name}
              <button
                onClick={() => toggle(lib.libraryId)}
                className="w-6 h-6 rounded-full hover:bg-orange-200 flex items-center justify-center transition cursor-pointer shrink-0"
                aria-label={`${lib.name} 선택 해제`}
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            </span>
          ))}
        </div>
      )}

      {overLimit && (
        <p className="text-xs text-red-500 mb-2">최대 {MAX_SELECT}개까지 선택 가능합니다</p>
      )}

      {/* Filter input */}
      <div className="relative mb-2">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
          <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
        </svg>
        <input
          type="text"
          placeholder="이름, 지역으로 필터"
          aria-label="전자도서관 이름·지역 필터"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          className="w-full pl-8 pr-4 py-2.5 rounded-xl border border-orange-100 bg-white text-base
            focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary transition
            placeholder:text-slate-400"
        />
      </div>

      {/* Library list */}
      <div className="max-h-48 overflow-y-auto scrollbar-thin space-y-0.5 bg-white rounded-2xl border border-orange-50 p-1 mb-4">
        {filteredLibs.length === 0 && (
          <p className="text-sm text-slate-500 text-center py-4">검색 결과 없음</p>
        )}
        {filteredLibs.map(lib => {
          const checked = draftIds.has(lib.libraryId)
          const disabled = !checked && draftIds.size >= MAX_SELECT
          return (
            <label
              key={lib.libraryId}
              className={`flex items-center gap-3 px-3 py-2 rounded-xl transition
                ${disabled ? 'opacity-40 cursor-not-allowed' : 'hover:bg-orange-50/50 cursor-pointer'}`}
            >
              <input
                type="checkbox"
                checked={checked}
                disabled={disabled}
                onChange={() => toggle(lib.libraryId)}
                className="w-4 h-4 accent-primary shrink-0"
              />
              <span className="text-sm text-slate-700 flex-1 truncate">{lib.name}</span>
              <span className="text-xs text-slate-500 shrink-0">{lib.region}</span>
            </label>
          )
        })}
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <button
          onClick={onCancel}
          className="flex-1 py-3 border border-orange-100 rounded-2xl text-sm text-slate-600
            hover:border-primary hover:text-primary transition cursor-pointer bg-white"
        >
          취소
        </button>
        <button
          onClick={() => onConfirm([...draftIds])}
          disabled={overLimit}
          className="flex-1 py-3 bg-primary text-white rounded-2xl font-semibold text-sm
            hover:bg-primary-dark active:scale-[0.98] transition disabled:opacity-40
            disabled:cursor-not-allowed cursor-pointer shadow-sm shadow-orange-200"
        >
          {draftIds.size === 0 ? '설정 해제' : `${draftIds.size}개 선택 완료`}
        </button>
      </div>
    </div>
  )
}

// --- 책 정보(제목)를 확보하지 못했을 때의 책 카드 ---
// '없는 책'과 '조회 실패'를 구분해 이유를 보여 준다. 화면 전체를 막지 않아 받은 도서관·중고 결과는 그대로 보인다

function BookInfoUnavailableCard({ isbn13, notice }: { isbn13: string; notice: string }) {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 mb-5 shadow-sm shadow-orange-50">
      <div className="flex gap-4 items-center">
        <div className="w-[72px] h-[100px] bg-orange-50 rounded-xl shrink-0 flex items-center justify-center">
          <svg aria-hidden="true" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
          </svg>
        </div>
        <div className="flex-1 min-w-0">
          {/* 책 카드의 제목 자리라 제목 수준으로 둔다(BookDetailCard의 h2와 같은 위치) */}
          <h2 className="text-sm font-semibold text-slate-700 leading-snug">{notice}</h2>
          <span className="mt-2 inline-block text-xs font-mono bg-orange-50 text-orange-700 px-2 py-0.5 rounded-lg">
            {isbn13}
          </span>
        </div>
      </div>
    </div>
  )
}

/**
 * 책 정보를 못 받았을 때 책 카드 문구. 서버의 YES24 조회 결과(identification.yes24)로 고른다.
 * 검색 요청 자체가 실패했거나 모르는 값이면 일반 문구.
 */
function bookInfoNoticeText(yes24?: string): string {
  switch (yes24) {
    case 'NOT_FOUND': return 'YES24에 등록되지 않은 책이에요'
    case 'SKIPPED': return '표준 ISBN이 없는 상품이라 책 정보를 확인할 수 없어요'
    case 'RATE_LIMITED':
    case 'DAILY_LIMIT': return '요청이 많아 책 정보를 잠시 불러오지 못했어요'
    default: return '책 정보를 잠시 불러오지 못했어요'
  }
}

// --- Header skeleton (cold-entry: book metadata not yet loaded) ---

function BookDetailCardSkeleton() {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 mb-5 shadow-sm shadow-orange-50 animate-pulse">
      <div className="flex gap-4">
        <div className="w-[72px] h-[100px] bg-orange-50 rounded-xl shrink-0" />
        <div className="flex-1 min-w-0 space-y-2 self-center">
          <div className="h-4 bg-slate-100 rounded w-4/5" />
          <div className="h-3 bg-slate-100 rounded w-1/2" />
          <div className="h-3 bg-slate-100 rounded w-2/5" />
          <div className="h-5 bg-orange-50 rounded-lg w-32 mt-2" />
        </div>
      </div>
    </div>
  )
}

// --- Main component ---

export function BookDetailPage({ isbn13, initialBook, onReset }: Props) {
  // Search API state
  const [searchResult, setSearchResult] = useState<SearchResponse | null>(null)
  const [searchLoading, setSearchLoading] = useState(true)
  const [searchError, setSearchError] = useState<string | null>(null)
  // 새 검색 결과마다 증가 — 공공도서관 목록의 누적 페이징 상태를 key 리마운트로 리셋
  const [searchSeq, setSearchSeq] = useState(0)

  // E-library state
  const [elibResult, setElibResult] = useState<ELibrarySearchResponse | null>(null)
  const [elibLoading, setElibLoading] = useState(false)
  const [elibError, setElibError] = useState<string | null>(null)
  const [savedElibIds, setSavedElibIds] = useState<number[]>(() => getSavedIds())

  // Location state
  const [useLocation, setUseLocation] = useState(false)
  const [locLoading, setLocLoading] = useState(false)
  const [locError, setLocError] = useState<string | null>(null)
  const userLatRef = useRef<number | null>(null)
  const userLonRef = useRef<number | null>(null)

  // Bottom sheet
  const [sheetOpen, setSheetOpen] = useState(false)
  // 전자도서관 선택 시트의 변경분 추적 (백드롭/취소 시 확인 다이얼로그 게이트)
  const [sheetDirty, setSheetDirty] = useState(false)
  // 변경분 폐기 확인 다이얼로그
  const [discardConfirmOpen, setDiscardConfirmOpen] = useState(false)

  // Cold-entry book metadata (Kakao) — initialBook이 없을 때 채워짐
  const [coldBook, setColdBook] = useState<BookCandidate | null>(null)
  // Cold-fetch 완료 플래그 — warm 진입은 처음부터 true, cold 진입은 fetch 종료(성공/실패) 후 true
  // e-lib 검색이 서버 응답 메타로 락되는 race condition 방지 + cold 진입 시 통합 검색 시작 시점(힌트 확보 후 1회)
  const [coldFetchDone, setColdFetchDone] = useState<boolean>(Boolean(initialBook))

  // 첫 통합 검색이 나갔는지 — cold 대기 중 위치가 허용되면 별도 요청 없이 첫 요청에 위치를 싣기 위해
  const searchStartedRef = useRef(false)

  // AbortControllers
  const searchAbort = useRef<AbortController | null>(null)
  const elibAbort = useRef<AbortController | null>(null)
  const coldBookAbort = useRef<AbortController | null>(null)

  // --- API calls with race guard ---

  function runSearch(lat?: number, lon?: number) {
    searchStartedRef.current = true
    searchAbort.current?.abort()
    const controller = new AbortController()
    searchAbort.current = controller
    setSearchLoading(true)
    setSearchError(null)
    // 이미 가진 후보 정보를 힌트로 보낸다 — YES24가 실패하거나 못 찾아도 서버가 책 정보·밀리 조회를 유지한다
    const hintBook = initialBook ?? coldBook
    const hint = hintBook
      ? { title: hintBook.title, author: hintBook.author, publisher: hintBook.publisher, cover: hintBook.coverUrl }
      : null
    searchMain(isbn13, lat, lon, controller.signal, hint)
      .then(res => {
        if (searchAbort.current === controller) {
          setSearchResult(res)
          setSearchSeq(seq => seq + 1)
        }
      })
      .catch(err => {
        if (err.name !== 'AbortError' && searchAbort.current === controller) {
          setSearchError(toUserMessage(err, '통합 검색 중 오류가 발생했습니다.'))
        }
      })
      .finally(() => {
        if (searchAbort.current === controller) setSearchLoading(false)
      })
  }

  function runElibSearch(ids: number[], title: string, author: string) {
    elibAbort.current?.abort()
    if (ids.length === 0) {
      setElibResult(null)
      setElibError(null)
      setElibLoading(false)
      return
    }
    const controller = new AbortController()
    elibAbort.current = controller
    setElibLoading(true)
    setElibError(null)
    searchELibraries(title, author, ids.join(','), controller.signal)
      .then(res => {
        if (elibAbort.current === controller) setElibResult(res)
      })
      .catch(err => {
        if (err.name !== 'AbortError' && elibAbort.current === controller) {
          setElibError(toUserMessage(err, '전자도서관 검색 중 오류가 발생했습니다.'))
        }
      })
      .finally(() => {
        if (elibAbort.current === controller) setElibLoading(false)
      })
  }

  // --- Display book: initialBook 우선, 콜드 진입 시 Kakao(coldBook), 그래도 없으면 searchResult.book(YES24·힌트) ---
  // Kakao 우선 이유: 검색 리스트(warm)와 동일한 소스로 통일해 카드 표기 불일치 방지

  const displayBook: BookCandidate | null = useMemo(() => {
    if (initialBook) return initialBook
    if (coldBook) return coldBook
    const fetched = searchResult?.book
    if (fetched && fetched.title) {
      return {
        title: fetched.title,
        author: fetched.author ?? '',
        isbn13: fetched.isbn13 ?? isbn13,
        publisher: fetched.publisher ?? '',
        coverUrl: fetched.coverUrl ?? '',
        publishedAt: '',
      }
    }
    return null
  }, [initialBook, coldBook, searchResult, isbn13])

  // --- Cold entry: initialBook이 없으면 Kakao에서 책 메타데이터 fetch ---

  useEffect(() => {
    if (initialBook) return
    coldBookAbort.current?.abort()
    const controller = new AbortController()
    coldBookAbort.current = controller
    // 통합 검색이 이 조회의 완료를 기다리므로 제한 시간을 둔다. 넘기면 힌트 없이 검색을 시작한다
    // (서버의 카카오 호출은 연결·읽기 각 1초 — 정상이면 이 안에 끝난다)
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, COLD_FETCH_TIMEOUT_MS)
    searchBooks(isbn13, 1, 1, controller.signal)
      .then(res => {
        if (controller.signal.aborted) return
        // exact ISBN match만 채택 — Kakao 가 다른 판본을 첫 결과로 줄 수 있음
        const found = res.items.find(b => b.isbn13 === isbn13)
        if (found) setColdBook(found)
      })
      .catch(() => {
        // Kakao 실패 → 힌트 없이 검색, searchResult.book(YES24) 폴백
      })
      .finally(() => {
        clearTimeout(timer)
        // 화면 이탈로 취소된 경우만 완료로 치지 않는다. 제한 시간 초과는 '실패로 완료'
        if (!controller.signal.aborted || timedOut) setColdFetchDone(true)
      })
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [initialBook, isbn13])

  // --- 통합 검색: warm은 즉시, cold는 Kakao 조회 '완료' 뒤 1회 ---
  // coldBook != null 이 아니라 완료(성공·오류·빈 결과·ISBN 불일치 모두)를 기다린다. 그래야 실패해도 검색이 시작되고,
  // 힌트 없는 요청과 힌트 있는 요청이 두 번 나가지 않는다. 대기 중 허용된 위치는 ref로 이 요청에 실린다

  useEffect(() => {
    if (!coldFetchDone) return
    runSearch(userLatRef.current ?? undefined, userLonRef.current ?? undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [coldFetchDone])

  useEffect(() => {
    return () => {
      searchAbort.current?.abort()
      elibAbort.current?.abort()
    }
  }, [])

  // --- Initial e-lib trigger: displayBook 가 처음 준비된 시점에 1회만 실행 ---

  useEffect(() => {
    if (!displayBook) return
    // cold 진입 시 Kakao(coldBook) 가 settle 될 때까지 대기
    // — 서버 응답이 먼저 와서 displayBook 이 서버 값으로 채워지는 race condition 방지
    if (!coldFetchDone) return
    if (savedElibIds.length === 0) return
    // 가드는 일부러 두지 않음:
    // displayBook(null→truthy)·coldFetchDone(false→true) 가 단방향 전이라 production 에선 1회만 fire,
    // Strict Mode dev 더블마운트에선 2회 fire 되지만 runElibSearch 내부 abort+재요청으로 안전.
    runElibSearch(savedElibIds, displayBook.title, displayBook.author)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [displayBook, coldFetchDone])

  // --- Helpers ---

  function handleGetLocation() {
    if (!navigator.geolocation) {
      setLocError('이 환경에서는 위치 검색을 지원하지 않습니다')
      return
    }
    setLocLoading(true)
    setLocError(null)
    navigator.geolocation.getCurrentPosition(
      pos => {
        const { latitude, longitude } = pos.coords
        userLatRef.current = latitude
        userLonRef.current = longitude
        setUseLocation(true)
        setLocLoading(false)
        // 첫 검색 전(cold 대기 중)이면 좌표만 남긴다 — 대기가 끝나면 위치를 실은 한 번의 요청이 나간다
        if (searchStartedRef.current) runSearch(latitude, longitude)
      },
      (err) => {
        setLocLoading(false)
        const msg =
          err.code === 1 ? '위치 권한이 거부되었습니다. 브라우저 설정에서 허용해주세요.' :
          err.code === 2 ? '위치 정보를 사용할 수 없습니다. 기기의 위치 서비스가 켜져 있는지 확인해주세요.' :
          err.code === 3 ? '위치 요청 시간이 초과되었습니다. 다시 시도해주세요.' :
          '위치 정보를 가져올 수 없습니다'
        setLocError(msg)
      },
    )
  }

  function handleElibSheetConfirm(ids: number[]) {
    setSavedElibIds(ids)
    saveIds(ids)
    setSheetDirty(false)
    setSheetOpen(false)
    if (displayBook) {
      runElibSearch(ids, displayBook.title, displayBook.author)
    }
  }

  // 시트 닫기 요청 (백드롭·취소·X 공통). dirty 면 확인 다이얼로그로 게이트.
  function requestSheetClose() {
    if (sheetDirty) {
      setDiscardConfirmOpen(true)
      return
    }
    setSheetOpen(false)
  }

  function discardSheetChanges() {
    setDiscardConfirmOpen(false)
    setSheetDirty(false)
    setSheetOpen(false)
  }

  // Get section statuses from metadata
  const usedBookProductUrl = searchResult?.usedBook?.productUrl
  const usedSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'USED_BOOK')
  const libSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'PUBLIC_LIBRARY')
  const libSectionStatus = libSection?.status
  const newBookSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'NEW_BOOK')
  const newBookNotice = newBookSection ? newBookNoticeText(newBookSection.status, newBookSection.reason) : null

  const hasSavedElibs = savedElibIds.length > 0

  // 통합 검색·카카오 조회가 모두 끝났는데도 책 제목이 없을 때. '없는 책'으로 단정하지 않는다 —
  // 호출 제한·장애·YES24 미등록을 구분해 책 카드에 이유를 보이고, 나머지 칸(도서관·중고 등)은 그대로 보여 준다
  const bookInfoNotice = !searchLoading && coldFetchDone && !displayBook
    ? bookInfoNoticeText(searchError ? undefined : searchResult?.metadata.identification?.yes24)
    : null

  return (
    <div>
      {/* Book detail card */}
      {displayBook ? (
        <BookDetailCard book={displayBook} />
      ) : bookInfoNotice ? (
        <BookInfoUnavailableCard isbn13={isbn13} notice={bookInfoNotice} />
      ) : (
        <BookDetailCardSkeleton />
      )}

      <div className="space-y-3">
        {/* ==================== PUBLIC LIBRARY SECTION ==================== */}
        {searchLoading ? (
          <SearchLoadingSkeleton />
        ) : searchError ? (
          <div className="p-4 bg-red-50 border border-red-100 rounded-2xl text-red-600 text-sm">
            {searchError}
          </div>
        ) : searchResult && (
          <>
            {/* Public Library */}
            {libSectionStatus === 'SKIPPED' && libSection?.reason === 'NON_STANDARD_ISBN' ? (
              // 서점 내부 코드 등 표준 ISBN이 아님 — 조회 자체가 무의미해 '미보유'로 보이지 않게 이유를 말한다 (F1)
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
              >
                <p className="text-sm text-slate-600 text-center py-2">
                  표준 ISBN이 없는 상품이라 도서관 소장 여부를 확인할 수 없어요
                </p>
              </SectionCard>
            ) : libSectionStatus === 'SKIPPED' ? (
              // Location not granted (NO_LOCATION) — show CTA card
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
              >
                <div className="text-center py-4">
                  <p className="text-sm text-slate-600 mb-3">
                    위치를 허용하면 근처 도서관의<br />소장 여부를 확인할 수 있어요
                  </p>
                  {locError && (
                    <p className="text-xs text-red-500 mb-2">{locError}</p>
                  )}
                  <button
                    onClick={handleGetLocation}
                    disabled={locLoading}
                    className="px-5 py-2.5 bg-primary text-white rounded-2xl text-sm font-semibold
                      hover:bg-primary-dark active:scale-[0.98] transition disabled:opacity-40
                      cursor-pointer shadow-sm shadow-orange-200 inline-flex items-center gap-2"
                  >
                    {locLoading ? (
                      <svg className="animate-spin" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                        <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
                      </svg>
                    ) : (
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                        <circle cx="12" cy="12" r="3" />
                        <path d="M12 1v4M12 19v4M4.22 4.22l2.83 2.83M16.95 16.95l2.83 2.83M1 12h4M19 12h4M4.22 19.78l2.83-2.83M16.95 7.05l2.83-2.83" />
                      </svg>
                    )}
                    위치 허용하기
                  </button>
                </div>
              </SectionCard>
            ) : libSectionStatus === 'FAILED' ? (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
              >
                <p className="text-sm text-slate-600 text-center py-2">검색에 실패했습니다</p>
              </SectionCard>
            ) : searchResult.publicLibraries.length === 0 &&
              !searchResult.metadata.publicLibraryHasMore ? (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
              >
                <p className="text-sm text-slate-600 text-center py-2">
                  {(searchResult.metadata.publicLibraryTotal ?? 0) > 0
                    ? '근처 도서관의 응답을 받지 못했어요'
                    : '근처에 조회할 도서관이 없습니다'}
                </p>
              </SectionCard>
            ) : (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
              >
                {useLocation && (
                  <div className="mb-2 flex items-center gap-2 text-xs text-emerald-600 bg-emerald-50 rounded-xl px-2.5 py-1.5">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                      <path d="M20 6L9 17l-5-5" />
                    </svg>
                    위치 기반 검색
                  </div>
                )}
                <PublicLibraryList
                  key={searchSeq}
                  isbn13={searchResult.book.isbn13 ?? isbn13}
                  lat={userLatRef.current}
                  lon={userLonRef.current}
                  initialLibraries={searchResult.publicLibraries}
                  initialTotal={searchResult.metadata.publicLibraryTotal}
                  initialNextOffset={searchResult.metadata.publicLibraryNextOffset}
                />
              </SectionCard>
            )}

            {/* ==================== E-LIBRARY SECTION ==================== */}

            {/* ... rendered below outside the searchResult block */}

            {/* ==================== SUBSCRIPTION SECTION ==================== */}
            {(() => {
              const subscriptionStatus = searchResult.metadata.sectionStatuses
                .find(s => s.section === 'SUBSCRIPTION')?.status

              const subscriptionReason = searchResult.metadata.sectionStatuses
                .find(s => s.section === 'SUBSCRIPTION')?.reason

              const sectionIcon = (
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="5" y="2" width="14" height="20" rx="2" />
                  <line x1="12" y1="18" x2="12" y2="18" />
                </svg>
              )

              if (subscriptionStatus === 'SKIPPED' && subscriptionReason === 'NO_METADATA') {
                return (
                  <SectionCard icon={sectionIcon} title="구독 서비스">
                    <p className="text-sm text-slate-600 text-center py-2">
                      책 정보를 확인하지 못해 구독 여부를 확인할 수 없어요
                    </p>
                  </SectionCard>
                )
              }

              if (!subscriptionStatus || subscriptionStatus === 'SKIPPED') {
                return null
              }

              if (subscriptionStatus === 'FAILED') {
                return (
                  <SectionCard icon={sectionIcon} title="구독 서비스">
                    <p className="text-sm text-slate-600 text-center py-2">
                      구독 정보 조회 실패. 잠시 후 다시 시도해주세요
                    </p>
                  </SectionCard>
                )
              }

              // SUCCESS — millie.available에 따라 분기
              const millie = searchResult.subscription.millie
              return (
                <SectionCard icon={sectionIcon} title="구독 서비스">
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-slate-600">밀리</span>
                    {millie.available ? (
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-slate-800 text-sm">{formatLabel(millie.format)}</span>
                        {isSafeUrl(millie.detailUrl) && (
                          <a
                            href={millie.detailUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-brand-millie/20
                              text-xs text-slate-800 font-medium hover:bg-brand-millie/30 transition"
                          >
                            밀리
                            <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                              <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                            </svg>
                          </a>
                        )}
                      </div>
                    ) : (
                      <span className="text-sm text-slate-500">구독 정보 없음</span>
                    )}
                  </div>
                </SectionCard>
              )
            })()}

            {/* ==================== USED BOOK / NEARBY STORE SECTIONS ==================== */}
            {usedSection?.reason !== 'NON_STANDARD_ISBN' && (
              <>
                <SectionCard title="중고도서" icon={<span aria-hidden="true" className="text-slate-400">↗</span>}>
                  <div className="flex items-center justify-between gap-3 py-1">
                    <p className="text-sm text-slate-600">중고 가격과 판매 정보를 확인해보세요</p>
                    <a href={isSafeUrl(usedBookProductUrl) ? usedBookProductUrl : `https://www.aladin.co.kr/shop/UsedShop/wuseditemall.aspx?ISBN=${encodeURIComponent(searchResult.book.isbn13 ?? isbn13)}`}
                      target="_blank" rel="noopener noreferrer"
                      aria-label="알라딘에서 확인 (중고 목록, 새 창)"
                      className="shrink-0 px-3 py-2 rounded-lg bg-brand-aladin/10 text-xs text-brand-aladin-dark font-medium">
                      알라딘에서 확인
                    </a>
                  </div>
                </SectionCard>
                <SectionCard title="가까운 알라딘 매장" icon={<span aria-hidden="true" className="text-slate-400">⌖</span>}>
                  {/* ISBN·위치가 바뀌면 다시 마운트 — 이전 목록이 새 조건에서 한 프레임이라도 보이지 않게 */}
                  <OffStoreList
                    key={`${searchResult.book.isbn13 ?? isbn13}:${userLatRef.current}:${userLonRef.current}`}
                    isbn13={searchResult.book.isbn13 ?? isbn13}
                    lat={userLatRef.current}
                    lon={userLonRef.current}
                    onRequestLocation={handleGetLocation}
                    locationLoading={locLoading}
                    locationError={locError}
                  />
                </SectionCard>
              </>
            )}

            {/* ==================== NEW BOOK SECTION ==================== */}
            {newBookNotice ? (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                    <line x1="3" y1="6" x2="21" y2="6" />
                    <path d="M16 10a4 4 0 0 1-8 0" />
                  </svg>
                }
                title="새책"
              >
                <p className="text-sm text-slate-600 text-center py-2">{newBookNotice}</p>
              </SectionCard>
            ) : searchResult.newBook && (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                    <line x1="3" y1="6" x2="21" y2="6" />
                    <path d="M16 10a4 4 0 0 1-8 0" />
                  </svg>
                }
                title="새책"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm text-slate-600">YES24</span>
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-slate-800 text-sm">{searchResult.newBook.price.toLocaleString()}원</span>
                    {isSafeUrl(searchResult.newBook.productUrl) && (
                    <a
                      href={searchResult.newBook.productUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-brand-yes24/10
                        text-xs text-brand-yes24-dark font-medium hover:bg-brand-yes24/20 transition"
                    >
                      YES24
                      <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                      </svg>
                    </a>
                    )}
                  </div>
                </div>
                {/* YES24 운영 안내: 데이터를 노출하는 화면에 출처 표기 */}
                <p className="mt-2 text-[11px] text-slate-500 text-right">가격 정보 제공: YES24</p>
              </SectionCard>
            )}
          </>
        )}

        {/* ==================== E-LIBRARY SECTION (independent from search) ==================== */}
        {elibLoading ? (
          <ElibLoadingSkeleton />
        ) : elibError ? (
          <SectionCard
            icon={
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
              </svg>
            }
            title="전자도서관"
          >
            <p className="text-sm text-red-500 text-center py-2">{elibError}</p>
            <button
              onClick={() => setSheetOpen(true)}
              className="w-full mt-2 py-2 text-xs text-primary hover:underline cursor-pointer"
            >
              도서관 다시 설정하기
            </button>
          </SectionCard>
        ) : elibResult ? (
          <SectionCard
            icon={
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
              </svg>
            }
            title="전자도서관"
          >
            <div className="flex items-center justify-end mb-2">
              <button
                onClick={() => setSheetOpen(true)}
                className="px-2 py-1 -mr-1 text-xs text-slate-500 hover:text-primary cursor-pointer"
              >
                설정 변경
              </button>
            </div>

            <div className="space-y-2 max-h-72 overflow-y-auto scrollbar-thin pr-0.5">
              {[...elibResult.results].sort((a, b) => a.libraryName.localeCompare(b.libraryName, 'ko')).map(result => (
                <div key={result.libraryId} className="bg-slate-50/50 border border-slate-100 rounded-xl p-3">
                  <div className="flex items-center justify-between mb-2">
                    <span className="font-semibold text-slate-800 text-sm">{result.libraryName}</span>
                    <div className="flex items-center gap-2">
                      <span className={`text-xs font-medium ${elibStatusColor[result.status] ?? 'text-slate-600'}`}>
                        {elibStatusLabel[result.status] ?? result.status}
                      </span>
                    </div>
                  </div>

                  {result.status === 'SUCCESS' && result.books.length === 0 && (
                    <p className="text-xs text-slate-500">소장 도서 없음</p>
                  )}

                  {result.books.map((b, i) => (
                    <div key={i} className="flex items-center gap-3 py-2 border-t border-slate-100 first:border-0">
                      {b.coverUrl && (
                        <img src={b.coverUrl} alt={b.title} className="w-8 h-11 object-cover rounded-lg shadow-sm shrink-0" />
                      )}
                      <div className="min-w-0 flex-1">
                        {isSafeUrl(b.detailUrl) ? (
                          <a
                            href={b.detailUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-sm font-medium text-slate-700 hover:text-primary hover:underline truncate block"
                          >
                            {b.title}
                          </a>
                        ) : (
                          <span className="text-sm font-medium text-slate-700 truncate block">{b.title}</span>
                        )}
                        <p className="text-xs text-slate-500 truncate">{b.author}</p>
                      </div>
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium shrink-0 ${b.available ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-600'}`}>
                        {b.available ? '대출가능' : '대출중'}
                      </span>
                    </div>
                  ))}
                </div>
              ))}
            </div>

            {elibResult.metadata.failures.length > 0 && (
              <div className="mt-2 p-3 bg-slate-50 rounded-xl">
                <p className="text-xs font-medium text-slate-600 mb-1">접속 실패</p>
                {elibResult.metadata.failures.map((f, i) => (
                  <p key={i} className="text-xs text-slate-500">{f.libraryName ?? `#${f.libraryId}`} — {f.reason}</p>
                ))}
              </div>
            )}
          </SectionCard>
        ) : !displayBook ? (
          // 전자도서관 검색은 제목·저자로 한다. 책 정보가 없으면 설정해도 검색할 수 없으므로 설정 버튼을 띄우지 않는다.
          // 아직 불러오는 중이면 비워 두고, 끝났는데도 없으면 이유를 보여 준다
          bookInfoNotice && <SectionCard
            icon={
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
              </svg>
            }
            title="전자도서관"
          >
            <p className="text-sm text-slate-600 text-center py-2">
              책 정보를 확인하지 못해 전자도서관을 검색할 수 없어요
            </p>
          </SectionCard>
        ) : !hasSavedElibs ? (
          // No saved libraries — empty state
          <SectionCard
            icon={
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
              </svg>
            }
            title="전자도서관"
          >
            <div className="text-center py-4">
              <p className="text-sm text-slate-600 mb-3">
                자주 이용하는 전자도서관을 설정하면<br />대출 가능 여부를 자동 확인해드려요
              </p>
              <button
                onClick={() => setSheetOpen(true)}
                className="px-5 py-2.5 bg-primary text-white rounded-2xl text-sm font-semibold
                  hover:bg-primary-dark active:scale-[0.98] transition cursor-pointer
                  shadow-sm shadow-orange-200"
              >
                전자도서관 설정하기
              </button>
            </div>
          </SectionCard>
        ) : null}

        {/* ==================== RESET BUTTON ==================== */}
        <button
          onClick={onReset}
          className="w-full py-3 border border-orange-100 rounded-2xl text-sm text-slate-600
            hover:border-primary hover:text-primary transition cursor-pointer bg-white"
        >
          처음부터 다시 검색
        </button>
      </div>

      {/* ==================== BOTTOM SHEET ==================== */}
      <BottomSheet
        open={sheetOpen}
        onClose={requestSheetClose}
        title="전자도서관 선택"
      >
        <ELibrarySelector
          initialIds={new Set(savedElibIds)}
          onConfirm={handleElibSheetConfirm}
          onCancel={requestSheetClose}
          onDirtyChange={setSheetDirty}
        />
      </BottomSheet>

      {/* ==================== DISCARD CONFIRM DIALOG ==================== */}
      <ConfirmDialog
        open={discardConfirmOpen}
        title="변경사항을 버리시겠어요?"
        message="저장하지 않으면 선택한 도서관 변경 내용이 사라집니다."
        confirmLabel="버리고 닫기"
        cancelLabel="계속 편집"
        destructive
        onConfirm={discardSheetChanges}
        onCancel={() => setDiscardConfirmOpen(false)}
      />
    </div>
  )
}
