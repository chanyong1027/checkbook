import { useState, useRef, useEffect, useCallback } from 'react'
import { searchBooks } from '../api'
import type { BookCandidate } from '../types'

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

function saveHistory(query: string) {
  const prev = getHistory().filter(q => q !== query)
  localStorage.setItem(HISTORY_KEY, JSON.stringify([query, ...prev].slice(0, MAX_HISTORY)))
}

function BookCover({ src, title }: { src?: string; title: string }) {
  if (src) {
    return (
      <img
        src={src}
        alt={title}
        className="w-12 h-[68px] object-cover rounded-lg shrink-0 shadow-sm"
      />
    )
  }
  return (
    <div className="w-12 h-[68px] bg-orange-50 rounded-lg shrink-0 flex items-center justify-center">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
      </svg>
    </div>
  )
}

function BookRowSkeleton() {
  return (
    <div className="flex gap-3 p-3.5 bg-white rounded-2xl border border-orange-50 animate-pulse">
      <div className="w-12 h-[68px] bg-orange-50 rounded-lg shrink-0" />
      <div className="flex-1 self-center space-y-2">
        <div className="h-3.5 bg-slate-100 rounded w-3/4" />
        <div className="h-3 bg-slate-100 rounded w-1/2" />
        <div className="h-3 bg-slate-100 rounded w-1/3" />
      </div>
    </div>
  )
}

export function BookSearchStep({ onSelect }: Props) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<BookCandidate[]>([])
  const [page, setPage] = useState(1)
  const [isEnd, setIsEnd] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const [history, setHistory] = useState<string[]>(getHistory)
  const currentQueryRef = useRef('')
  const sentinelRef = useRef<HTMLDivElement>(null)

  async function doSearch(q: string) {
    if (!q.trim()) return
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
      setError(err instanceof Error ? err.message : '검색 실패')
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
    localStorage.removeItem(HISTORY_KEY)
    setHistory([])
  }

  return (
    <div>
      {/* Title */}
      <div className="mb-5">
        <h1 className="text-2xl font-bold text-slate-900 tracking-tight">책 검색</h1>
        <p className="text-sm text-slate-400 mt-0.5">제목, 저자, ISBN으로 찾아보세요</p>
      </div>

      {/* Search form */}
      <form onSubmit={handleSearch} className="mb-4">
        <div className="flex gap-2">
          <div className="flex-1 relative">
            <input
              type="text"
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="예) 채식주의자"
              className="w-full pl-4 pr-10 py-3 rounded-2xl border border-orange-100 bg-white text-slate-900
                placeholder:text-slate-300 focus:outline-none focus:ring-2 focus:ring-primary/30
                focus:border-primary transition text-[15px] shadow-sm"
              maxLength={200}
              autoFocus
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-300 hover:text-slate-400 cursor-pointer"
              >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>
          <button
            type="submit"
            disabled={loading || !query.trim()}
            className="px-5 py-3 bg-primary text-white rounded-2xl font-semibold hover:bg-primary-dark
              active:scale-95 transition disabled:opacity-40 disabled:cursor-not-allowed
              flex items-center gap-1.5 cursor-pointer shadow-sm shadow-orange-200"
          >
            {loading ? (
              <svg className="animate-spin" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
              </svg>
            )}
          </button>
        </div>
      </form>

      {/* Recent history */}
      {!searched && history.length > 0 && (
        <div className="mb-5">
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-slate-400">최근 검색</span>
            <button
              onClick={clearHistory}
              className="text-xs text-slate-300 hover:text-slate-400 cursor-pointer"
            >
              전체 삭제
            </button>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {history.map(q => (
              <button
                key={q}
                onClick={() => handleHistoryClick(q)}
                className="px-3 py-1.5 rounded-full bg-white border border-orange-100 text-sm text-slate-600
                  hover:border-primary hover:text-primary transition cursor-pointer"
              >
                {q}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div className="p-4 bg-red-50 border border-red-100 rounded-2xl text-red-600 text-sm mb-4">
          {error}
        </div>
      )}

      {/* Empty */}
      {searched && results.length === 0 && (
        <div className="text-center py-14">
          <div className="w-14 h-14 bg-orange-50 rounded-2xl flex items-center justify-center mx-auto mb-3">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
              <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
            </svg>
          </div>
          <p className="text-slate-400 text-sm">검색 결과가 없습니다</p>
        </div>
      )}

      {/* Results */}
      {results.length > 0 && (
        <div className="space-y-2">
          {results.map(book => (
            <button
              key={book.isbn13}
              onClick={() => onSelect(book)}
              className="w-full flex gap-3 p-3.5 bg-white rounded-2xl border border-orange-50
                hover:border-primary/40 hover:shadow-sm active:scale-[0.98] transition text-left
                cursor-pointer group"
            >
              <BookCover src={book.coverUrl} title={book.title} />
              <div className="flex-1 min-w-0 self-center">
                <p className="font-semibold text-slate-800 truncate text-[14px] group-hover:text-primary transition leading-snug">
                  {book.title}
                </p>
                <p className="text-xs text-slate-400 mt-0.5 truncate">{book.author}</p>
                <p className="text-xs text-slate-300 truncate">{book.publisher}</p>
              </div>
              <div className="self-center text-slate-200 group-hover:text-primary/50 transition shrink-0">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M9 18l6-6-6-6" />
                </svg>
              </div>
            </button>
          ))}

          {/* Sentinel + 로딩 인디케이터 */}
          {!isEnd && (
            <div ref={sentinelRef} className="py-2">
              {loadingMore && (
                <div className="flex justify-center py-2">
                  <svg className="animate-spin text-orange-300" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                    <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
                  </svg>
                </div>
              )}
              {!loadingMore && (
                <div className="space-y-2">
                  <BookRowSkeleton />
                  <BookRowSkeleton />
                </div>
              )}
            </div>
          )}

          {isEnd && results.length > PAGE_SIZE && (
            <p className="text-center text-xs text-slate-300 py-3">모든 결과를 불러왔습니다</p>
          )}
        </div>
      )}
    </div>
  )
}
