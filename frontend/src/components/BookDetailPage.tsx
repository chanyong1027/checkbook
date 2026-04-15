import { useState, useEffect, useRef } from 'react'
import { searchMain, searchELibraries, getELibraries } from '../api'
import type {
  BookCandidate,
  SearchResponse,
  ELibraryInfo,
  ELibrarySearchResponse,
} from '../types'
import { OffStoreList } from './OffStoreList'
import { BookDetailCard } from './shared/BookDetailCard'
import { SectionCard } from './shared/SectionCard'
import { Skeleton } from './shared/Skeleton'
import { BottomSheet } from './shared/BottomSheet'

function isSafeUrl(url: string | null | undefined): url is string {
  if (!url) return false
  try {
    const { protocol } = new URL(url)
    return protocol === 'http:' || protocol === 'https:'
  } catch {
    return false
  }
}

interface Props {
  book: BookCandidate
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
  FAILED: 'text-red-400',
  TIMEOUT: 'text-orange-400',
}

const elibStatusLabel: Record<string, string> = {
  SUCCESS: '완료',
  FAILED: '실패',
  TIMEOUT: '타임아웃',
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

function ELibrarySelector({
  initialIds,
  onConfirm,
  onCancel,
}: {
  initialIds: Set<number>
  onConfirm: (ids: number[]) => void
  onCancel: () => void
}) {
  const [libraries, setLibraries] = useState<ELibraryInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [draftIds, setDraftIds] = useState<Set<number>>(() => new Set(initialIds))
  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    getELibraries()
      .then(setLibraries)
      .catch(e => setError(e instanceof Error ? e.message : '목록 로드 실패'))
      .finally(() => setLoading(false))
  }, [])

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
          <span className={`ml-1.5 ${overLimit ? 'text-red-400' : 'text-slate-300'}`}>
            {draftIds.size}/{MAX_SELECT}
          </span>
        </p>
        {draftIds.size > 0 && (
          <button onClick={clearAll} className="text-xs text-primary hover:underline cursor-pointer">
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
              className="inline-flex items-center gap-1 pl-2.5 pr-1.5 py-1 rounded-full
                bg-orange-50 border border-orange-200 text-xs text-orange-700 font-medium"
            >
              {lib.name}
              <button
                onClick={() => toggle(lib.libraryId)}
                className="w-4 h-4 rounded-full hover:bg-orange-200 flex items-center justify-center transition cursor-pointer shrink-0"
                aria-label={`${lib.name} 선택 해제`}
              >
                <svg width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            </span>
          ))}
        </div>
      )}

      {overLimit && (
        <p className="text-xs text-red-400 mb-2">최대 {MAX_SELECT}개까지 선택 가능합니다</p>
      )}

      {/* Filter input */}
      <div className="relative mb-2">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-300" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
          <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
        </svg>
        <input
          type="text"
          placeholder="이름, 지역으로 필터"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          className="w-full pl-8 pr-4 py-2.5 rounded-xl border border-orange-100 bg-white text-sm
            focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary transition
            placeholder:text-slate-300"
        />
      </div>

      {/* Library list */}
      <div className="max-h-48 overflow-y-auto scrollbar-thin space-y-0.5 bg-white rounded-2xl border border-orange-50 p-1 mb-4">
        {filteredLibs.length === 0 && (
          <p className="text-sm text-slate-300 text-center py-4">검색 결과 없음</p>
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
              <span className="text-xs text-slate-300 shrink-0">{lib.region}</span>
            </label>
          )
        })}
      </div>

      {/* Action buttons */}
      <div className="flex gap-2">
        <button
          onClick={onCancel}
          className="flex-1 py-3 border border-orange-100 rounded-2xl text-sm text-slate-400
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

// --- Main component ---

export function BookDetailPage({ book, onReset }: Props) {
  // Search API state
  const [searchResult, setSearchResult] = useState<SearchResponse | null>(null)
  const [searchLoading, setSearchLoading] = useState(true)
  const [searchError, setSearchError] = useState<string | null>(null)

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

  // AbortControllers
  const searchAbort = useRef<AbortController | null>(null)
  const elibAbort = useRef<AbortController | null>(null)

  // --- API calls with race guard ---

  function runSearch(lat?: number, lon?: number) {
    searchAbort.current?.abort()
    const controller = new AbortController()
    searchAbort.current = controller
    setSearchLoading(true)
    setSearchError(null)
    searchMain(book.isbn13, lat, lon, controller.signal)
      .then(res => {
        if (searchAbort.current === controller) setSearchResult(res)
      })
      .catch(err => {
        if (err.name !== 'AbortError' && searchAbort.current === controller) {
          setSearchError(err instanceof Error ? err.message : '검색 실패')
        }
      })
      .finally(() => {
        if (searchAbort.current === controller) setSearchLoading(false)
      })
  }

  function runElibSearch(ids: number[]) {
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
    searchELibraries(book.isbn13, ids.join(','), book.title, controller.signal)
      .then(res => {
        if (elibAbort.current === controller) setElibResult(res)
      })
      .catch(err => {
        if (err.name !== 'AbortError' && elibAbort.current === controller) {
          setElibError(err instanceof Error ? err.message : '검색 실패')
        }
      })
      .finally(() => {
        if (elibAbort.current === controller) setElibLoading(false)
      })
  }

  // --- Mount ---

  useEffect(() => {
    runSearch()

    // E-library: getSavedIds() already validates (Array.isArray, integer > 0, max 20)
    const savedIds = getSavedIds()
    if (savedIds.length > 0) runElibSearch(savedIds)

    return () => {
      searchAbort.current?.abort()
      elibAbort.current?.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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
        runSearch(latitude, longitude)
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
    setSheetOpen(false)
    runElibSearch(ids)
  }

  // Get section statuses from metadata
  const libSectionStatus = searchResult?.metadata.sectionStatuses
    .find(s => s.section === 'PUBLIC_LIBRARY')?.status
  const newBookSectionStatus = searchResult?.metadata.sectionStatuses
    .find(s => s.section === 'NEW_BOOK')?.status

  const hasSavedElibs = savedElibIds.length > 0

  return (
    <div>
      {/* Book detail card */}
      <BookDetailCard book={book} />

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
            {libSectionStatus === 'SKIPPED' ? (
              // Location not granted — show CTA card
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
                source="정보나루 기준"
              >
                <div className="text-center py-4">
                  <p className="text-sm text-slate-400 mb-3">
                    위치를 허용하면 근처 도서관의<br />소장 여부를 확인할 수 있어요
                  </p>
                  {locError && (
                    <p className="text-xs text-red-400 mb-2">{locError}</p>
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
                source="정보나루 기준"
              >
                <p className="text-sm text-slate-400 text-center py-2">검색에 실패했습니다</p>
              </SectionCard>
            ) : searchResult.publicLibraries.length === 0 ? (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title="공공도서관"
                source="정보나루 기준"
              >
                <p className="text-sm text-slate-400 text-center py-2">소장 도서관이 없습니다</p>
              </SectionCard>
            ) : (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                }
                title={`공공도서관 ${searchResult.publicLibraries.length}개`}
                source="정보나루 기준"
              >
                {useLocation && (
                  <div className="mb-2 flex items-center gap-2 text-xs text-emerald-600 bg-emerald-50 rounded-xl px-2.5 py-1.5">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                      <path d="M20 6L9 17l-5-5" />
                    </svg>
                    위치 기반 검색
                  </div>
                )}
                <div className="max-h-52 overflow-y-auto scrollbar-thin">
                  {searchResult.publicLibraries.map((lib, i) => (
                    <div key={i} className="py-2.5 border-b border-slate-50 last:border-0">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-slate-700 truncate">{lib.libraryName}</p>
                          <p className="text-xs text-slate-300 mt-0.5">{lib.distance}km · {lib.address}</p>
                        </div>
                        <div className="shrink-0 flex flex-col items-end gap-1">
                          <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.hasBook ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-400'}`}>
                            {lib.hasBook ? '보유' : '미보유'}
                          </span>
                          {lib.hasBook && (
                            <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.loanAvailable ? 'bg-blue-50 text-blue-600' : 'bg-orange-50 text-orange-500'}`}>
                              {lib.loanAvailable ? '대출가능' : '대출중'}
                            </span>
                          )}
                        </div>
                      </div>
                      {/* Link buttons */}
                      <div className="flex items-center gap-1.5 mt-2 flex-wrap">
                        {isSafeUrl(lib.homepage) && (
                          <a
                            href={lib.homepage}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-slate-50
                              border border-slate-100 text-xs text-slate-500 hover:border-primary
                              hover:text-primary transition"
                          >
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                              <circle cx="12" cy="12" r="10" />
                              <path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                            </svg>
                            홈페이지
                          </a>
                        )}
                        {lib.latitude != null && lib.longitude != null && (
                          <>
                            <a
                              href={`https://map.kakao.com/link/search/${encodeURIComponent(lib.libraryName)}`}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs
                                text-[#3A1D1D] bg-[#FEE500] hover:bg-[#FDD835] transition font-medium"
                            >
                              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                                <circle cx="12" cy="10" r="3" />
                              </svg>
                              장소 정보
                            </a>
                            <a
                              href={`https://map.kakao.com/link/to/${encodeURIComponent(lib.libraryName)},${lib.latitude},${lib.longitude}`}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs
                                text-white bg-[#3A1D1D] hover:bg-black transition font-medium"
                            >
                              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                                <polygon points="3 11 22 2 13 21 11 13 3 11" />
                              </svg>
                              길찾기
                            </a>
                          </>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </SectionCard>
            )}

            {/* ==================== E-LIBRARY SECTION ==================== */}

            {/* ... rendered below outside the searchResult block */}

            {/* ==================== USED BOOK SECTION ==================== */}
            {searchResult.usedBook && (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13" />
                  </svg>
                }
                title="중고도서"
                source="알라딘 기준"
              >
                <div className="space-y-0">
                  {([
                    { label: '개인 판매', price: searchResult.usedBook.userUsedPrice, url: searchResult.usedBook.userUsedUrl },
                    { label: '알라딘 온라인', price: searchResult.usedBook.aladinUsedPrice, url: searchResult.usedBook.aladinUsedUrl },
                  ] as const).map(({ label, price, url }) => (
                    <div key={label} className="flex items-center justify-between py-2.5 border-b border-slate-50 last:border-0">
                      <p className="text-sm text-slate-600">{label}</p>
                      <div className="flex items-center gap-2">
                        {price != null ? (
                          <>
                            <span className="font-semibold text-slate-800 text-sm">{price.toLocaleString()}원</span>
                            {isSafeUrl(url) && (
                              <a
                                href={url}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-[#e8400c]/10
                                  text-xs text-[#e8400c] font-medium hover:bg-[#e8400c]/20 transition"
                              >
                                알라딘
                                <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                                  <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                                </svg>
                              </a>
                            )}
                          </>
                        ) : (
                          <span className="text-sm text-slate-400">재고 없음</span>
                        )}
                      </div>
                    </div>
                  ))}
                  <OffStoreList
                    isbn13={searchResult.book.isbn13 ?? book.isbn13}
                    lat={userLatRef.current}
                    lon={userLonRef.current}
                    spaceUsedPrice={searchResult.usedBook.spaceUsedPrice}
                    spaceUsedUrl={searchResult.usedBook.spaceUsedUrl}
                    onRequestLocation={handleGetLocation}
                  />
                </div>
              </SectionCard>
            )}

            {/* ==================== NEW BOOK SECTION ==================== */}
            {newBookSectionStatus === 'FAILED' ? (
              <SectionCard
                icon={
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                    <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                    <line x1="3" y1="6" x2="21" y2="6" />
                    <path d="M16 10a4 4 0 0 1-8 0" />
                  </svg>
                }
                title="새책"
                source="알라딘 기준"
              >
                <p className="text-sm text-slate-400 text-center py-2">가격 정보를 가져올 수 없습니다</p>
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
                source="알라딘 기준"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm text-slate-600">알라딘</span>
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-slate-800 text-sm">{searchResult.newBook.price.toLocaleString()}원</span>
                    {isSafeUrl(searchResult.newBook.productUrl) && (
                    <a
                      href={searchResult.newBook.productUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-[#e8400c]/10
                        text-xs text-[#e8400c] font-medium hover:bg-[#e8400c]/20 transition"
                    >
                      알라딘
                      <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                      </svg>
                    </a>
                    )}
                  </div>
                </div>
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
            source="실시간 검색"
          >
            <p className="text-sm text-red-400 text-center py-2">{elibError}</p>
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
            source="실시간 검색"
          >
            <div className="flex items-center justify-end mb-2">
              <button
                onClick={() => setSheetOpen(true)}
                className="text-xs text-slate-300 hover:text-primary cursor-pointer"
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
                      <span className={`text-xs font-medium ${elibStatusColor[result.status] ?? 'text-slate-400'}`}>
                        {elibStatusLabel[result.status] ?? result.status}
                      </span>
                    </div>
                  </div>

                  {result.status === 'SUCCESS' && result.books.length === 0 && (
                    <p className="text-xs text-slate-300">소장 도서 없음</p>
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
                        <p className="text-xs text-slate-300 truncate">{b.author}</p>
                      </div>
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium shrink-0 ${b.available ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-400'}`}>
                        {b.available ? '대출가능' : '대출중'}
                      </span>
                    </div>
                  ))}
                </div>
              ))}
            </div>

            {elibResult.metadata.failures.length > 0 && (
              <div className="mt-2 p-3 bg-slate-50 rounded-xl">
                <p className="text-xs font-medium text-slate-400 mb-1">접속 실패</p>
                {elibResult.metadata.failures.map((f, i) => (
                  <p key={i} className="text-xs text-slate-300">{f.libraryName ?? `#${f.libraryId}`} — {f.reason}</p>
                ))}
              </div>
            )}
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
            source="실시간 검색"
          >
            <div className="text-center py-4">
              <p className="text-sm text-slate-400 mb-3">
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
          className="w-full py-3 border border-orange-100 rounded-2xl text-sm text-slate-400
            hover:border-primary hover:text-primary transition cursor-pointer bg-white"
        >
          처음부터 다시 검색
        </button>
      </div>

      {/* ==================== BOTTOM SHEET ==================== */}
      <BottomSheet
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        title="전자도서관 선택"
      >
        <ELibrarySelector
          initialIds={new Set(savedElibIds)}
          onConfirm={handleElibSheetConfirm}
          onCancel={() => setSheetOpen(false)}
        />
      </BottomSheet>
    </div>
  )
}
