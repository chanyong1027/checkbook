import { useState, useEffect, useMemo, useRef } from 'react'
import { searchBooks, searchMain, searchELibraries, getELibraries } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type {
  BookCandidate,
  MillieAvailability,
  SearchResponse,
  SearchSectionStatus,
  ELibraryInfo,
  ELibraryResult,
  ELibrarySearchResponse,
} from '../types'
import { OffStoreList } from './OffStoreList'
import { PublicLibraryList } from './PublicLibraryList'
import { BottomSheet } from './shared/BottomSheet'
import { ConfirmDialog } from './shared/ConfirmDialog'
import {
  BookIcon,
  ClockIcon,
  HeadphonesIcon,
  LibraryIcon,
  RefreshIcon,
  SearchIcon,
  SettingsIcon,
  SpinnerIcon,
  StoreIcon,
  TabletIcon,
} from './shared/icons'
import { BookPanel, type SummaryItem } from './detail/BookPanel'
import { DetailSection, ExternalButton, GroupHeading, SectionNotice, SectionSkeleton, StatusChip, type Tone } from './detail/parts'
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

// 전자도서관 사이트 하나의 결과를 상태 칩으로. 사이트가 실패하면 '없음'과 구분해 보인다
function elibSiteStatus(result: ELibraryResult): { tone: Tone; label: string } {
  switch (result.status) {
    case 'FAILED': return { tone: 'danger', label: '접속 실패' }
    case 'TIMEOUT': return { tone: 'unknown', label: '응답 없음' }
    // 이 도서관 사이트의 하루 조회 상한(작은 사이트 보호) — 자정에 풀린다
    case 'DAILY_LIMIT': return { tone: 'unknown', label: '오늘 조회 마감' }
  }
  if (result.books.some(b => b.available)) return { tone: 'ok', label: '대출가능' }
  if (result.books.length > 0) return { tone: 'off', label: '대출중' }
  return { tone: 'off', label: '소장 없음' }
}

// 대출가능 → 대출중·소장 없음 → 확인 못 함 순
function elibRank(result: ELibraryResult): number {
  const tone = elibSiteStatus(result).tone
  return tone === 'ok' ? 0 : tone === 'off' ? 1 : 2
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
    case 'EBOOK_AND_AUDIOBOOK': return '전자책·오디오북'
    default: return '구독 가능'
  }
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
        <SpinnerIcon size={28} strokeWidth={2.2} className="text-ink-400" />
      </div>
    )
  }

  if (error) {
    return <div role="alert" className="p-3 bg-status-danger-soft rounded-2xl text-status-danger text-meta">{error}</div>
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-center justify-between mb-2">
        <p className="text-meta font-semibold text-ink-700">
          도서관 선택
          <span className={`ml-1.5 ${overLimit ? 'text-status-danger' : 'text-ink-500'}`}>
            {draftIds.size}/{MAX_SELECT}
          </span>
        </p>
        {draftIds.size > 0 && (
          <button onClick={clearAll} className="min-h-10 px-2 -mr-2 text-label font-semibold text-primary hover:underline cursor-pointer">
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
              className="inline-flex items-center gap-1 pl-3 pr-1 py-1 rounded-full
                bg-accent-soft border border-accent/30 text-label text-accent-strong font-semibold"
            >
              {lib.name}
              <button
                onClick={() => toggle(lib.libraryId)}
                className="w-7 h-7 rounded-full hover:bg-accent/20 flex items-center justify-center transition cursor-pointer shrink-0"
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
        <p className="text-label text-status-danger mb-2">최대 {MAX_SELECT}개까지 선택할 수 있어요</p>
      )}

      {/* Filter input */}
      <div className="relative mb-2">
        <SearchIcon size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" />
        <input
          type="text"
          placeholder="이름, 지역으로 필터"
          aria-label="전자도서관 이름·지역 필터"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-ink-200 bg-white text-body
            focus:outline-none focus:ring-4 focus:ring-accent/30 focus:border-primary transition
            placeholder:text-ink-500"
        />
      </div>

      {/* Library list */}
      <div className="max-h-56 overflow-y-auto scrollbar-thin space-y-0.5 bg-white rounded-2xl border border-ink-100 p-1 mb-4">
        {filteredLibs.length === 0 && (
          <p className="text-meta text-ink-600 text-center py-4">맞는 도서관이 없어요</p>
        )}
        {filteredLibs.map(lib => {
          const checked = draftIds.has(lib.libraryId)
          const disabled = !checked && draftIds.size >= MAX_SELECT
          return (
            <label
              key={lib.libraryId}
              className={`flex items-center gap-3 px-3 min-h-11 rounded-xl transition
                ${disabled ? 'opacity-40 cursor-not-allowed' : 'hover:bg-ink-50 cursor-pointer'}`}
            >
              <input
                type="checkbox"
                checked={checked}
                disabled={disabled}
                onChange={() => toggle(lib.libraryId)}
                className="w-4 h-4 accent-primary shrink-0"
              />
              <span className="text-body text-ink-900 flex-1 truncate">{lib.name}</span>
              <span className="text-label text-ink-500 shrink-0">{lib.region}</span>
            </label>
          )
        })}
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <button
          onClick={onCancel}
          className="flex-1 min-h-12 border border-ink-200 rounded-xl text-meta font-semibold text-ink-900
            hover:border-ink-400 transition cursor-pointer bg-white"
        >
          취소
        </button>
        <button
          onClick={() => onConfirm([...draftIds])}
          disabled={overLimit}
          className="flex-1 min-h-12 bg-primary text-white rounded-xl font-bold text-meta
            hover:bg-primary-dark transition disabled:opacity-40
            disabled:cursor-not-allowed cursor-pointer"
        >
          {draftIds.size === 0 ? '설정 해제' : `${draftIds.size}개 선택 완료`}
        </button>
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

  // --- 칸 상태(메타데이터) ---
  const usedBookProductUrl = searchResult?.usedBook?.productUrl
  const usedSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'USED_BOOK')
  const libSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'PUBLIC_LIBRARY')
  const libSectionStatus = libSection?.status
  const newBookSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'NEW_BOOK')
  const newBookNotice = newBookSection ? newBookNoticeText(newBookSection.status, newBookSection.reason) : null
  const subSection = searchResult?.metadata.sectionStatuses.find(s => s.section === 'SUBSCRIPTION')
  // 다시 검색이 실패하면 옛 결과를 쓰지 않는다(runSearch는 searchResult를 지우지 않는다) — 본문은 이 값만 본다
  const result = searchError ? null : searchResult
  const libraries = result?.publicLibraries ?? []
  const libHasMore = result?.metadata.publicLibraryHasMore === true

  const hasSavedElibs = savedElibIds.length > 0

  // 통합 검색·카카오 조회가 모두 끝났는데도 책 제목이 없을 때. '없는 책'으로 단정하지 않는다 —
  // 호출 제한·장애·YES24 미등록을 구분해 책 패널에 이유를 보이고, 나머지 칸(도서관·중고 등)은 그대로 보여 준다
  const bookInfoNotice = !searchLoading && coldFetchDone && !displayBook
    ? bookInfoNoticeText(searchError ? undefined : searchResult?.metadata.identification?.yes24)
    : null

  // 표준 ISBN이 아니면 도서관·매장 모두 위치로 풀 수 없다 — 그때만 위치를 묻지 않는다
  const locationNeeded = libSection?.reason !== 'NON_STANDARD_ISBN'

  // --- 한눈 요약(각 칸의 결과로 화면에서 계산) ---
  const loadingItem = { value: '확인 중', tone: 'loading' as const }
  const failedItem = { value: '불러오지 못함', tone: 'warn' as const }

  function librarySummary(): Pick<SummaryItem, 'value' | 'tone'> {
    if (searchLoading) return loadingItem
    if (searchError || !searchResult) return failedItem
    if (libSectionStatus === 'SKIPPED') {
      if (libSection?.reason === 'NON_STANDARD_ISBN') return { value: '확인 불가', tone: 'neutral' }
      if (libSection?.reason === 'DAILY_LIMIT') return { value: '오늘 조회 마감', tone: 'neutral' }
      return { value: '위치 필요', tone: 'neutral' }
    }
    if (libSectionStatus === 'FAILED' && libraries.length === 0) return { value: '응답 없음', tone: 'warn' }
    if (libraries.length === 0) {
      return (searchResult.metadata.publicLibraryTotal ?? 0) > 0
        ? { value: '응답 없음', tone: 'warn' }
        : { value: '근처에 없음', tone: 'off' }
    }
    const available = libraries.filter(l => l.hasBook && l.loanAvailable).length
    const held = libraries.filter(l => l.hasBook).length
    if (available > 0) return { value: `${available}곳 대출가능`, tone: 'ok' }
    if (held > 0) return { value: libHasMore ? `가까운 ${held}곳 대출중` : `${held}곳 모두 대출중`, tone: 'off' }
    return { value: '가까운 곳엔 없음', tone: 'off' }
  }

  function elibSummary(): Pick<SummaryItem, 'value' | 'tone'> {
    if (!displayBook) return bookInfoNotice ? { value: '확인 불가', tone: 'neutral' } : loadingItem
    if (!hasSavedElibs) return { value: '설정 필요', tone: 'neutral' }
    if (elibError) return failedItem
    if (elibLoading || !elibResult) return loadingItem
    const checked = elibResult.results.filter(r => ['ok', 'off'].includes(elibSiteStatus(r).tone))
    if (checked.length === 0) return { value: '확인 못 함', tone: 'warn' }
    const available = checked.filter(r => elibSiteStatus(r).tone === 'ok').length
    if (available > 0) return { value: `${available}곳 대출가능`, tone: 'ok' }
    if (elibResult.results.some(r => r.books.length > 0)) return { value: '모두 대출중', tone: 'off' }
    return { value: '소장 없음', tone: 'off' }
  }

  function subscriptionSummary(): Pick<SummaryItem, 'value' | 'tone'> {
    if (searchLoading) return loadingItem
    if (searchError || !searchResult) return failedItem
    if (subSection?.status === 'FAILED') return failedItem
    if (subSection?.status !== 'SUCCESS') {
      return subSection?.reason === 'DAILY_LIMIT'
        ? { value: '오늘 조회 마감', tone: 'neutral' }
        : { value: '확인 불가', tone: 'neutral' }
    }
    const millie = searchResult.subscription.millie
    return millie.available ? { value: formatLabel(millie.format), tone: 'ok' } : { value: '없음', tone: 'off' }
  }

  function newBookSummary(): Pick<SummaryItem, 'value' | 'tone'> {
    if (searchLoading) return loadingItem
    if (searchError || !searchResult) return failedItem
    if (!newBookNotice && searchResult.newBook) {
      return { value: `${searchResult.newBook.price.toLocaleString()}원`, tone: 'neutral' }
    }
    if (newBookSection?.reason === 'DAILY_LIMIT') return { value: '오늘 조회 마감', tone: 'neutral' }
    if (newBookSection?.reason === 'RATE_LIMITED' || newBookSection?.status === 'FAILED') return { value: '확인 못 함', tone: 'warn' }
    return { value: '가격 정보 없음', tone: 'neutral' }
  }

  const summary: SummaryItem[] = [
    { href: '#library', label: '근처 도서관', ...librarySummary() },
    { href: '#elibrary', label: '전자도서관', ...elibSummary() },
    { href: '#subscription', label: '밀리의서재', ...subscriptionSummary() },
    { href: '#buy', label: '새 책', ...newBookSummary() },
  ]

  function handleSummaryNavigate(href: string) {
    if (href === '#elibrary' && displayBook && !hasSavedElibs) {
      setSheetOpen(true)
      return
    }
    const id = href.slice(1)
    const target = document.getElementById(id)
    if (!target) return
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    target.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
    // 키보드·화면 읽기 사용자도 그 칸에서 이어 가게 칸 제목으로 포커스(불러오는 중이면 제목이 없어 스크롤만)
    document.getElementById(`${id}-title`)?.focus({ preventScroll: true })
  }

  // --- 칸 내용 ---

  const showLibraryList =
    !!result &&
    libSectionStatus !== 'SKIPPED' &&
    !(libSectionStatus === 'FAILED' && !libHasMore) &&
    !(libraries.length === 0 && !libHasMore)

  function libraryBody() {
    if (!result) return <SectionNotice>도서관 정보를 불러오지 못했어요</SectionNotice>
    if (libSectionStatus === 'SKIPPED' && libSection?.reason === 'NON_STANDARD_ISBN') {
      // 서점 내부 코드 등 표준 ISBN이 아님 — 조회 자체가 무의미해 '미보유'로 보이지 않게 이유를 말한다 (F1)
      return <SectionNotice>표준 ISBN이 없는 상품이라 도서관 소장 여부를 확인할 수 없어요</SectionNotice>
    }
    if (libSectionStatus === 'SKIPPED' && libSection?.reason === 'DAILY_LIMIT') {
      // 정보나루 하루 호출 예산 소진 — 위치 안내로 보이면 안 된다(위치는 이미 있음)
      return <SectionNotice>오늘은 조회가 많아 도서관 소장 여부를 확인할 수 없어요. 내일 다시 시도해주세요</SectionNotice>
    }
    if (libSectionStatus === 'SKIPPED') {
      return <SectionNotice>위치를 허용하면 가까운 도서관의 소장·대출 여부를 보여 드려요</SectionNotice>
    }
    if (!showLibraryList) {
      // 조회한 도서관이 모두 실패하면 서버가 FAILED로 준다(D7-0). 더 볼 도서관이 남았으면 목록(건너뜀 수 + 더 보기)으로 간다
      return (
        <SectionNotice>
          {libSectionStatus === 'FAILED' || (result.metadata.publicLibraryTotal ?? 0) > 0
            ? '근처 도서관의 응답을 받지 못했어요'
            : '근처에 조회할 도서관이 없어요'}
        </SectionNotice>
      )
    }
    return (
      <>
        {/* 정보나루는 전날 자료이고 대출중만 '불가'로 준다(예약·상호대차 중은 가능으로 옴, 운영처 문의 결과 못 바꿈) — D7-0 */}
        <p className="mb-3 text-label text-ink-600">예약·상호대차 중인 책도 '대출가능'으로 보일 수 있어요</p>
        <PublicLibraryList
          key={searchSeq}
          isbn13={result.book.isbn13 ?? isbn13}
          lat={userLatRef.current}
          lon={userLonRef.current}
          initialLibraries={result.publicLibraries}
          initialTotal={result.metadata.publicLibraryTotal}
          initialNextOffset={result.metadata.publicLibraryNextOffset}
        />
      </>
    )
  }

  function elibBody() {
    if (!displayBook) {
      // 전자도서관 검색은 제목·저자로 한다. 책 정보가 없으면 설정해도 검색할 수 없으므로 설정 버튼을 띄우지 않는다
      return <SectionNotice>책 정보를 확인하지 못해 전자도서관을 검색할 수 없어요</SectionNotice>
    }
    if (!hasSavedElibs) {
      return (
        <div>
          <p className="text-body text-ink-700">자주 쓰는 전자도서관을 고르면 이 책을 빌릴 수 있는지 바로 확인해 드려요</p>
          <button
            type="button"
            onClick={() => setSheetOpen(true)}
            className="mt-3 min-h-11 px-5 rounded-xl bg-primary text-white text-meta font-bold hover:bg-primary-dark transition cursor-pointer"
          >
            전자도서관 고르기
          </button>
        </div>
      )
    }
    if (elibError) {
      return (
        <div className="flex items-center justify-between gap-3">
          <p role="alert" className="text-meta text-status-danger">{elibError}</p>
          <button
            type="button"
            onClick={() => runElibSearch(savedElibIds, displayBook.title, displayBook.author)}
            className="inline-flex items-center gap-1.5 min-h-10 px-2 text-meta font-semibold text-primary hover:text-primary-dark cursor-pointer shrink-0"
          >
            <RefreshIcon size={14} strokeWidth={2} />
            다시 시도
          </button>
        </div>
      )
    }
    if (!elibResult) return <SectionNotice>내 전자도서관을 확인하고 있어요</SectionNotice>

    const sites = [...elibResult.results].sort(
      (a, b) => elibRank(a) - elibRank(b) || a.libraryName.localeCompare(b.libraryName, 'ko'),
    )
    const checked = sites.filter(r => ['ok', 'off'].includes(elibSiteStatus(r).tone)).length
    const available = sites.filter(r => elibSiteStatus(r).tone === 'ok').length
    return (
      <>
        <p className="mb-3 text-meta text-ink-600">
          {checked === 0 ? (
            '고른 전자도서관에서 답을 받지 못했어요'
          ) : (
            <>
              {sites.length}곳 중 <span className="font-bold text-ink-900">{available}곳 대출가능</span>
            </>
          )}
        </p>
        <ul role="list" className="grid gap-3 md:grid-cols-2">
          {sites.map(site => {
            const status = elibSiteStatus(site)
            return (
              <li key={site.libraryId} className="rounded-xl border border-ink-100 p-4">
                <div className="flex items-start justify-between gap-3">
                  <span className="text-body font-semibold text-ink-900">{site.libraryName}</span>
                  <StatusChip tone={status.tone}>{status.label}</StatusChip>
                </div>
                {site.books.length > 0 && (
                  <ul role="list" className="mt-2">
                    {site.books.map((b, i) => (
                      <li key={i} className="flex items-center gap-3 py-2 border-t border-ink-100 first:border-t-0">
                        {b.coverUrl && (
                          <img src={b.coverUrl} alt="" className="w-8 h-11 object-cover rounded shrink-0 ring-1 ring-black/5" />
                        )}
                        <div className="min-w-0 flex-1">
                          {isSafeUrl(b.detailUrl) ? (
                            <a
                              href={b.detailUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-meta font-semibold text-ink-900 hover:underline underline-offset-2 line-clamp-2"
                            >
                              {b.title}
                            </a>
                          ) : (
                            <span className="text-meta font-semibold text-ink-900 line-clamp-2">{b.title}</span>
                          )}
                          <p className="text-label text-ink-600 truncate">{b.author}</p>
                        </div>
                        {/* 한 권이면 사이트 칩과 같은 말이라 빼고, 여러 판본일 때만 판본마다 보인다 */}
                        {site.books.length > 1 && (
                          <span className={`text-label font-semibold shrink-0 ${b.available ? 'text-status-ok' : 'text-ink-600'}`}>
                            {b.available ? '대출가능' : '대출중'}
                          </span>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
        {elibResult.metadata.failures.length > 0 && (
          <div className="mt-3 rounded-xl border border-dashed border-ink-300 px-4 py-3">
            <p className="text-meta font-semibold text-ink-700 mb-1">접속하지 못한 곳</p>
            {elibResult.metadata.failures.map((f, i) => (
              <p key={i} className="text-label text-ink-600">
                {f.libraryName ?? `#${f.libraryId}`} — {f.reason}
              </p>
            ))}
          </div>
        )}
      </>
    )
  }

  function subscriptionBody() {
    if (!result) return <SectionNotice>구독 정보를 불러오지 못했어요</SectionNotice>
    if (subSection?.status === 'SKIPPED' && subSection.reason === 'NO_METADATA') {
      return <SectionNotice>책 정보를 확인하지 못해 구독 여부를 확인할 수 없어요</SectionNotice>
    }
    if (subSection?.status === 'SKIPPED' && subSection.reason === 'DAILY_LIMIT') {
      return <SectionNotice>오늘은 조회가 많아 구독 여부를 확인할 수 없어요. 내일 다시 시도해주세요</SectionNotice>
    }
    if (subSection?.status === 'FAILED') {
      return <SectionNotice>구독 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요</SectionNotice>
    }
    if (subSection?.status !== 'SUCCESS') return <SectionNotice>구독 여부를 확인할 수 없어요</SectionNotice>

    const millie = result.subscription.millie
    if (!millie.available) return <SectionNotice>밀리의서재에는 없어요</SectionNotice>
    return (
      <div className="flex flex-wrap items-center gap-3">
        <StatusChip tone="ok">{formatLabel(millie.format)} 있음</StatusChip>
        {isSafeUrl(millie.detailUrl) && (
          <ExternalButton href={millie.detailUrl} label="밀리에서 보기 (새 창)" className="sm:ml-auto">
            밀리에서 보기
          </ExternalButton>
        )}
      </div>
    )
  }

  const usedUrl = isSafeUrl(usedBookProductUrl)
    ? usedBookProductUrl
    : `https://www.aladin.co.kr/shop/UsedShop/wuseditemall.aspx?ISBN=${encodeURIComponent(searchResult?.book.isbn13 ?? isbn13)}`

  function buyBody() {
    const newBook = result?.newBook
    return (
      <div className="grid gap-3 md:grid-cols-2">
        <div className="rounded-xl border border-ink-100 p-4 flex flex-col gap-3">
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-meta font-semibold text-ink-700">새 책 · YES24</span>
            {newBook && !newBookNotice && (
              <span className="text-heading font-bold text-ink-900 tabular-nums">{newBook.price.toLocaleString()}원</span>
            )}
          </div>
          {!result ? (
            <p className="text-meta text-ink-600">가격 정보를 불러오지 못했어요</p>
          ) : newBookNotice ? (
            <p className="text-meta text-ink-600">{newBookNotice}</p>
          ) : newBook ? (
            <>
              {isSafeUrl(newBook.productUrl) && (
                <ExternalButton primary href={newBook.productUrl} label="YES24에서 보기 (새 창)">
                  YES24에서 보기
                </ExternalButton>
              )}
              {/* YES24 운영 안내: 데이터를 노출하는 화면에 출처 표기 */}
              <p className="text-caption text-ink-600 text-right">가격 정보 제공: YES24</p>
            </>
          ) : (
            <p className="text-meta text-ink-600">YES24에 판매 정보가 없어요</p>
          )}
        </div>
        {usedSection?.reason !== 'NON_STANDARD_ISBN' && (
          <div className="rounded-xl border border-ink-100 p-4 flex flex-col gap-3">
            <div className="flex items-baseline justify-between gap-2">
              <span className="text-meta font-semibold text-ink-700">중고 · 알라딘</span>
              <span className="text-label text-ink-600">가격은 알라딘에서 확인</span>
            </div>
            <ExternalButton href={usedUrl} label="알라딘에서 보기 (새 창, 중고 목록)">
              알라딘에서 보기
            </ExternalButton>
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-content md:px-6 md:py-8 lg:grid lg:grid-cols-[320px_minmax(0,1fr)] lg:gap-10 lg:items-start">
      <BookPanel
        isbn13={isbn13}
        book={displayBook}
        bookNotice={bookInfoNotice}
        summary={summary}
        location={{
          granted: useLocation,
          loading: locLoading,
          error: locError,
          needed: locationNeeded,
          onRequest: handleGetLocation,
        }}
        onReset={onReset}
        onNavigate={handleSummaryNavigate}
      />

      <div className="mt-2 md:mt-6 lg:mt-0 pb-10 flex flex-col gap-2 md:gap-4">
        {searchError && (
          <div role="alert" className="mx-4 md:mx-0 flex items-center justify-between gap-3 rounded-2xl bg-status-danger-soft px-4 py-3">
            <p className="text-meta text-status-danger">{searchError}</p>
            <button
              type="button"
              onClick={() => runSearch(userLatRef.current ?? undefined, userLonRef.current ?? undefined)}
              className="inline-flex items-center gap-1.5 min-h-10 px-2 text-meta font-semibold text-primary-dark hover:underline cursor-pointer shrink-0"
            >
              <RefreshIcon size={14} strokeWidth={2} />
              다시 시도
            </button>
          </div>
        )}

        {/* ==================== 빌리기 ==================== */}
        <GroupHeading title="빌리기" desc="무료로 빌릴 수 있는 곳" />
        {searchLoading ? (
          <SectionSkeleton id="library" rows={4} />
        ) : (
          <DetailSection
            id="library"
            icon={<LibraryIcon size={20} />}
            title="근처 공공도서관"
            aside={
              showLibraryList ? (
                <span className="inline-flex items-center gap-1 text-label text-ink-600">
                  <ClockIcon size={14} />
                  전날 자료 기준
                </span>
              ) : undefined
            }
          >
            {libraryBody()}
          </DetailSection>
        )}

        {elibLoading || (!displayBook && !bookInfoNotice) ? (
          <SectionSkeleton id="elibrary" rows={2} />
        ) : (
          <DetailSection
            id="elibrary"
            icon={<TabletIcon size={20} />}
            title="전자도서관"
            aside={
              displayBook && hasSavedElibs ? (
                <button
                  type="button"
                  onClick={() => setSheetOpen(true)}
                  className="inline-flex items-center gap-1 min-h-10 px-2 -mr-2 text-meta font-semibold text-ink-700 hover:text-ink-900 cursor-pointer"
                >
                  <SettingsIcon size={16} />
                  내 전자도서관
                </button>
              ) : undefined
            }
          >
            {elibBody()}
          </DetailSection>
        )}

        {/* ==================== 읽기 ==================== */}
        <GroupHeading title="읽기" desc="구독으로 바로 읽기" />
        {searchLoading ? (
          <SectionSkeleton id="subscription" rows={1} />
        ) : (
          <DetailSection id="subscription" icon={<HeadphonesIcon size={20} />} title="밀리의서재">
            {subscriptionBody()}
          </DetailSection>
        )}

        {/* ==================== 사기 ==================== */}
        <GroupHeading title="사기" desc="새 책 · 중고 · 가까운 매장" />
        {searchLoading ? (
          <SectionSkeleton id="buy" rows={2} />
        ) : (
          <DetailSection id="buy" icon={<BookIcon size={20} />} title="새 책 · 중고">
            {buyBody()}
          </DetailSection>
        )}

        {/* 검색 중에도 둔다 — 위치를 허용하면 매장 요청이 다시 검색을 기다리지 않고 바로 나간다 */}
        {usedSection?.reason !== 'NON_STANDARD_ISBN' && (
          <DetailSection id="stores" icon={<StoreIcon size={20} />} title="가까운 알라딘 중고매장">
            {/* ISBN·위치가 바뀌면 다시 마운트 — 이전 목록이 새 조건에서 한 프레임이라도 보이지 않게 */}
            <OffStoreList
              key={`${isbn13}:${userLatRef.current}:${userLonRef.current}`}
              isbn13={searchResult?.book.isbn13 ?? isbn13}
              lat={userLatRef.current}
              lon={userLonRef.current}
            />
          </DetailSection>
        )}

        <p className="px-4 md:px-0 pt-2 text-caption text-ink-600">
          정보 제공: 도서관 정보나루 · YES24 · 밀리의서재 · 각 전자도서관
        </p>
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
