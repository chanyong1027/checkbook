import { useState, useRef, useEffect, useCallback } from 'react'
import { searchBooks } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type { BookCandidate } from '../types'
import { FeaturedBooksSection } from './FeaturedBooksSection'
import {
  BookIcon,
  ChevronRightIcon,
  CloseIcon,
  HeadphonesIcon,
  LibraryIcon,
  SearchIcon,
  SpinnerIcon,
  StoreIcon,
  TabletIcon,
} from './shared/icons'

interface Props {
  onSelect: (book: BookCandidate) => void
}

const HISTORY_KEY = 'checkbook_history'
const MAX_HISTORY = 6
const PAGE_SIZE = 10

function getHistory(): string[] {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
  } catch {
    return []
  }
}

// 저장소를 막은 브라우저에서는 최근 검색을 남기지 못할 뿐, 검색은 그대로 된다
function saveHistory(query: string) {
  try {
    const prev = getHistory().filter(q => q !== query)
    localStorage.setItem(HISTORY_KEY, JSON.stringify([query, ...prev].slice(0, MAX_HISTORY)))
  } catch {
    /* 최근 검색 없이 진행 */
  }
}

// 출판 연도만(판본 구분용). 형식이 낯설면 표시하지 않는다
function publishedYear(publishedAt: string): string | null {
  const m = publishedAt?.match(/^(\d{4})/)
  return m ? m[1] : null
}

function BookCover({ src }: { src?: string }) {
  if (src) {
    return (
      <img
        src={src}
        alt=""
        loading="lazy"
        className="w-[60px] h-[87px] object-cover rounded-md shrink-0 ring-1 ring-black/5 shadow-sm"
      />
    )
  }
  return (
    <div className="w-[60px] h-[87px] bg-ink-50 rounded-md shrink-0 flex items-center justify-center text-ink-300">
      <BookIcon size={20} />
    </div>
  )
}

function BookRowSkeleton() {
  return (
    <div className="flex gap-4 p-4 bg-white rounded-2xl border border-ink-100 animate-pulse">
      <div className="w-[60px] h-[87px] bg-ink-100 rounded-md shrink-0" />
      <div className="flex-1 self-center space-y-2">
        <div className="h-4 bg-ink-100 rounded w-3/4" />
        <div className="h-3.5 bg-ink-100 rounded w-1/2" />
        <div className="h-3.5 bg-ink-100 rounded w-1/3" />
      </div>
    </div>
  )
}

// 홈 첫 화면에서 '한 번에 확인하는 곳'을 알린다(상세 화면의 칸 순서와 같게)
const SOURCES = [
  { label: '근처 공공도서관 소장·대출', short: '근처 도서관', Icon: LibraryIcon },
  { label: '내 전자도서관 대출', short: '전자도서관', Icon: TabletIcon },
  { label: '밀리의서재 구독', short: '밀리의서재', Icon: HeadphonesIcon },
  { label: '새 책 가격 · 중고 · 가까운 매장', short: '새 책 · 중고', Icon: StoreIcon },
]

const SEARCH_CACHE_KEY = 'cb_search_cache'
const SEARCH_CACHE_MAX_ITEMS = 30

interface SearchCache {
  query: string
  results: BookCandidate[]
  page: number
  isEnd: boolean
}

const CANDIDATE_STRING_FIELDS = ['title', 'author', 'publisher', 'isbn13', 'coverUrl', 'publishedAt'] as const

// 화면이 쓰는 필드가 모두 문자열인 항목만(손상되거나 예전 형식의 캐시가 화면을 깨뜨리지 않게)
function isCachedCandidate(v: unknown): v is BookCandidate {
  if (!v || typeof v !== 'object') return false
  const b = v as Record<string, unknown>
  return CANDIDATE_STRING_FIELDS.every(key => typeof b[key] === 'string')
}

// 저장소를 막은 브라우저(사파리 사생활 보호 등)는 접근 자체가 예외를 던진다 — 지우기 실패도 삼킨다
function dropSearchCache() {
  try {
    sessionStorage.removeItem(SEARCH_CACHE_KEY)
  } catch {
    /* 캐시 없이 진행 */
  }
}

// 상세에서 뒤로 왔을 때의 검색 결과. 첫 렌더 전에 읽어야 큰 소개가 한 번 그려졌다가 접히는 깜빡임이 없다.
// 렌더 중에 불리므로 어떤 경우에도 예외를 밖으로 내보내지 않는다(내보내면 검색 화면 전체가 깨진다)
function readSearchCache(): SearchCache | null {
  let raw: string | null
  try {
    raw = sessionStorage.getItem(SEARCH_CACHE_KEY)
  } catch {
    return null
  }
  if (!raw) return null
  try {
    const cache = JSON.parse(raw)
    if (!cache || typeof cache.query !== 'string' || !Array.isArray(cache.results)) {
      dropSearchCache()
      return null
    }
    const safeResults = (cache.results as unknown[]).filter(isCachedCandidate).slice(0, SEARCH_CACHE_MAX_ITEMS)
    const safePage = Number.isInteger(cache.page) && cache.page >= 1 ? cache.page : 1
    return { query: cache.query, results: safeResults, page: safePage, isEnd: cache.isEnd === true }
  } catch {
    dropSearchCache()
    return null
  }
}

export function BookSearchStep({ onSelect }: Props) {
  const [cached] = useState(readSearchCache)
  const [query, setQuery] = useState(cached?.query ?? '')
  const [results, setResults] = useState<BookCandidate[]>(cached?.results ?? [])
  const [page, setPage] = useState(cached?.page ?? 1)
  const [isEnd, setIsEnd] = useState(cached?.isEnd ?? false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(cached !== null)
  const [history, setHistory] = useState<string[]>(getHistory)
  const currentQueryRef = useRef(cached?.query ?? '')
  const sentinelRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // 처음 열 때만 검색칸에 포커스(autoFocus는 폼이 다시 그려질 때마다 포커스를 옮겨 모바일 키보드를 다시 띄운다).
  // 상세에서 뒤로 와 결과를 복원할 때는 옮기지 않는다
  useEffect(() => {
    if (!cached) inputRef.current?.focus()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Save cache when search state changes
  useEffect(() => {
    if (!searched) return
    try {
      sessionStorage.setItem(SEARCH_CACHE_KEY, JSON.stringify({
        query,
        results: results.slice(0, SEARCH_CACHE_MAX_ITEMS),
        page: Math.min(page, Math.ceil(SEARCH_CACHE_MAX_ITEMS / PAGE_SIZE) + 1),
        isEnd: results.length <= SEARCH_CACHE_MAX_ITEMS ? isEnd : false,
      }))
    } catch {
      // quota exceeded — ignore
    }
  }, [results, query, page, isEnd, searched])

  async function doSearch(q: string) {
    if (!q.trim()) return
    dropSearchCache()
    currentQueryRef.current = q.trim()
    setLoading(true)
    setError(null)
    setSearched(false)
    setResults([])
    setPage(1)
    setIsEnd(false)
    try {
      const res = await searchBooks(q.trim(), 1, PAGE_SIZE)
      if (currentQueryRef.current !== q.trim()) return
      setResults(res.items)
      setIsEnd(res.pagination.isEnd)
      setPage(2)
      setSearched(true)
      saveHistory(q.trim())
      setHistory(getHistory())
    } catch (err) {
      setError(toUserMessage(err, '도서 검색 중 오류가 발생했습니다.'))
    } finally {
      setLoading(false)
    }
  }

  const loadMore = useCallback(async () => {
    const q = currentQueryRef.current
    if (!q || loadingMore || isEnd) return
    setLoadingMore(true)
    try {
      const res = await searchBooks(q, page, PAGE_SIZE)
      if (currentQueryRef.current !== q) return
      setResults(prev => [...prev, ...res.items])
      setIsEnd(res.pagination.isEnd)
      setPage(prev => prev + 1)
    } catch {
      // 추가 로드 실패는 조용히 무시
    } finally {
      setLoadingMore(false)
    }
  }, [page, isEnd, loadingMore])

  // IntersectionObserver: sentinel이 보이면 다음 페이지 로드
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel) return

    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting) {
          loadMore()
        }
      },
      { threshold: 0.1 }
    )

    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [loadMore])

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    doSearch(query)
  }

  function handleHistoryClick(q: string) {
    setQuery(q)
    doSearch(q)
  }

  function clearHistory() {
    try {
      localStorage.removeItem(HISTORY_KEY)
    } catch {
      /* 지우지 못해도 화면에서는 비운다 */
    }
    setHistory([])
  }

  // 검색을 시작하면 큰 소개를 접고 검색칸만 위에 남긴다
  const compact = searched || loading || results.length > 0 || error !== null

  const searchForm = (
    <form onSubmit={handleSearch} role="search" className="flex gap-2">
      <div className="flex-1 relative">
        <span className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink-500">
          <SearchIcon size={20} />
        </span>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="책 제목, 저자, ISBN"
          aria-label="책 검색"
          className="w-full h-12 md:h-14 pl-11 pr-11 rounded-2xl border-[1.5px] border-ink-900 bg-white text-ink-900
            text-body md:text-body-lg placeholder:text-ink-500
            focus:outline-none focus:border-primary focus:ring-4 focus:ring-accent/30 transition"
          maxLength={200}
        />
        {query && (
          <button
            type="button"
            onClick={() => setQuery('')}
            aria-label="검색어 지우기"
            className="absolute right-1 top-1/2 -translate-y-1/2 w-11 h-11 flex items-center justify-center
              text-ink-500 hover:text-ink-900 cursor-pointer"
          >
            <CloseIcon size={18} strokeWidth={2} />
          </button>
        )}
      </div>
      <button
        type="submit"
        disabled={loading || !query.trim()}
        aria-label="검색"
        className="h-12 md:h-14 min-w-12 md:min-w-0 md:px-7 bg-primary text-white rounded-2xl font-bold text-body-lg
          hover:bg-primary-dark transition disabled:opacity-40 disabled:cursor-not-allowed
          flex items-center justify-center gap-1.5 cursor-pointer"
      >
        {loading ? <SpinnerIcon size={20} strokeWidth={2.2} /> : <SearchIcon size={20} strokeWidth={2.2} className="md:hidden" />}
        <span className="hidden md:inline">검색</span>
      </button>
    </form>
  )

  const historyChips = !searched && history.length > 0 && (
    <div className="mt-5">
      <div className="flex items-center justify-between mb-1">
        <span className="text-label font-semibold text-ink-600">최근 검색</span>
        <button
          onClick={clearHistory}
          className="min-h-10 px-2 -mr-2 text-label text-ink-600 hover:text-ink-900 cursor-pointer"
        >
          전체 삭제
        </button>
      </div>
      <div className="flex flex-wrap gap-2">
        {history.map(q => (
          <button
            key={q}
            onClick={() => handleHistoryClick(q)}
            className="min-h-10 px-4 rounded-full bg-white border border-ink-200 text-meta text-ink-700
              hover:border-ink-500 hover:text-ink-900 transition cursor-pointer"
          >
            {q}
          </button>
        ))}
      </div>
    </div>
  )

  return (
    <div>
      {/* 검색 폼은 늘 같은 자리에 둔다 — 큰 소개를 접고 펼 때 폼이 새로 만들어지면 입력 상태·포커스가 흔들린다 */}
      <section className="bg-white border-b border-ink-100">
        <div className="mx-auto max-w-content px-4 md:px-6">
          <div
            className={
              compact
                ? 'py-4 md:py-6'
                : 'py-7 md:py-12 lg:py-16 lg:grid lg:grid-cols-[minmax(0,1fr)_360px] lg:gap-14 lg:items-center'
            }
          >
            <div>
              {compact ? (
                <h1 className="sr-only">책 검색 결과</h1>
              ) : (
                <>
                  <p className="text-label md:text-meta font-bold text-accent-strong">
                    도서관 · 전자도서관 · 구독 · 서점을 한 번에
                  </p>
                  <h1 className="mt-2 text-display lg:text-display-lg font-bold text-ink-900">책, 어디 있을까?</h1>
                  <p className="mt-3 text-body md:text-body-lg text-ink-600 max-w-xl">
                    읽고 싶은 책 한 권을 검색하면 근처 도서관 대출부터 전자도서관, 밀리의서재, 새 책 가격까지
                    한 화면에 모아 보여 드려요.
                  </p>
                </>
              )}
              <div className={compact ? 'max-w-2xl' : 'mt-6 max-w-2xl'}>{searchForm}</div>
              {!compact && (
                <>
                  <ul role="list" className="mt-4 flex flex-wrap gap-2 lg:hidden" aria-label="한 번에 확인하는 곳">
                    {SOURCES.map(({ short, Icon }) => (
                      <li key={short} className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full bg-ink-50 text-label text-ink-700">
                        <Icon size={15} />
                        {short}
                      </li>
                    ))}
                  </ul>
                  <div className="max-w-2xl">{historyChips}</div>
                </>
              )}
            </div>

            {!compact && (
              <aside className="hidden lg:block rounded-2xl border border-ink-100 bg-surface px-6 py-5">
                <h2 className="text-meta font-bold text-ink-900 mb-1">한 번에 확인하는 곳</h2>
                <ul role="list">
                  {SOURCES.map(({ label, Icon }, i) => (
                    <li
                      key={label}
                      className={'flex items-center gap-3 min-h-[52px] text-body text-ink-700' + (i > 0 ? ' border-t border-ink-100' : '')}
                    >
                      <span className="w-9 h-9 rounded-xl bg-white flex items-center justify-center text-ink-700 shrink-0">
                        <Icon size={18} />
                      </span>
                      {label}
                    </li>
                  ))}
                </ul>
              </aside>
            )}
          </div>
        </div>
      </section>

      <div className="mx-auto max-w-content px-4 md:px-6 py-6 md:py-10">
        {error && (
          <div role="alert" className="p-4 mb-6 bg-status-danger-soft rounded-2xl text-status-danger text-meta">
            {error}
          </div>
        )}

        {/* 추천(검색 전에만) */}
        {!searched && !loading && !error && <FeaturedBooksSection onSelect={onSelect} />}

        {loading && results.length === 0 && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            <BookRowSkeleton />
            <BookRowSkeleton />
            <BookRowSkeleton />
            <BookRowSkeleton />
          </div>
        )}

        {searched && results.length === 0 && (
          <div className="text-center py-16">
            <div className="w-14 h-14 bg-ink-50 rounded-2xl flex items-center justify-center mx-auto mb-3 text-ink-400">
              <SearchIcon size={24} />
            </div>
            <p className="text-body text-ink-700">검색 결과가 없어요</p>
            <p className="mt-1 text-meta text-ink-600">제목을 짧게 줄이거나 ISBN으로 찾아보세요</p>
          </div>
        )}

        {/* 결과: 모바일 1열, PC 2열. 같은 책의 여러 판본을 고를 수 있게 제목 2줄 + 저자·출판사·연도 */}
        {results.length > 0 && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            {results.map(book => {
              const year = publishedYear(book.publishedAt)
              return (
                <button
                  key={book.isbn13}
                  onClick={() => onSelect(book)}
                  className="w-full flex gap-4 p-4 bg-white rounded-2xl border border-ink-100
                    hover:border-ink-300 hover:shadow-sm transition text-left cursor-pointer group"
                >
                  <BookCover src={book.coverUrl} />
                  <div className="flex-1 min-w-0 self-center">
                    <p className="text-body font-semibold text-ink-900 line-clamp-2 group-hover:underline underline-offset-2">
                      {book.title}
                    </p>
                    <p className="mt-1 text-meta text-ink-600 truncate">{book.author}</p>
                    <p className="text-meta text-ink-500 truncate">
                      {[book.publisher, year].filter(Boolean).join(' · ')}
                    </p>
                  </div>
                  <span className="self-center text-ink-300 group-hover:text-ink-600 transition shrink-0">
                    <ChevronRightIcon size={18} strokeWidth={2} />
                  </span>
                </button>
              )
            })}

            {/* Sentinel + 로딩 표시 */}
            {!isEnd && (
              <div ref={sentinelRef} className="col-span-full py-2">
                {loadingMore ? (
                  <div className="flex justify-center py-2 text-ink-400">
                    <SpinnerIcon size={20} strokeWidth={2.2} />
                  </div>
                ) : (
                  <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                    <BookRowSkeleton />
                    <BookRowSkeleton />
                  </div>
                )}
              </div>
            )}

            {isEnd && results.length > PAGE_SIZE && (
              <p className="col-span-full text-center text-label text-ink-600 py-3">모든 결과를 불러왔어요</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
