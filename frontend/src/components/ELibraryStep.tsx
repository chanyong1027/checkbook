import { useState, useEffect, useRef } from 'react'
import { getELibraries, searchELibraries } from '../api'
import type { BookCandidate, ELibraryInfo, ELibrarySearchResponse } from '../types'

interface Props {
  book: BookCandidate
  onNext: () => void
  onBack: () => void
  onReset: () => void
}

const ELIB_SELECTION_KEY = 'checkbook_elib_ids'
const MAX_SELECT = 20

function getSavedIds(): number[] {
  try {
    return JSON.parse(localStorage.getItem(ELIB_SELECTION_KEY) || '[]')
  } catch {
    return []
  }
}

function saveIds(ids: number[]) {
  localStorage.setItem(ELIB_SELECTION_KEY, JSON.stringify(ids))
}

const statusColor: Record<string, string> = {
  SUCCESS: 'text-emerald-600',
  FAILED: 'text-red-400',
  TIMEOUT: 'text-orange-400',
}

const statusLabel: Record<string, string> = {
  SUCCESS: '완료',
  FAILED: '실패',
  TIMEOUT: '타임아웃',
}

function BookDetailCard({ book }: { book: BookCandidate }) {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 mb-5 shadow-sm shadow-orange-50">
      <div className="flex gap-4">
        {book.coverUrl ? (
          <img
            src={book.coverUrl}
            alt={book.title}
            className="w-[72px] h-[100px] object-cover rounded-xl shadow-md shadow-orange-100 shrink-0"
          />
        ) : (
          <div className="w-[72px] h-[100px] bg-orange-50 rounded-xl shrink-0 flex items-center justify-center">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
          </div>
        )}
        <div className="flex-1 min-w-0">
          <h2 className="font-bold text-slate-800 text-base leading-snug mb-1 line-clamp-2">
            {book.title}
          </h2>
          <p className="text-sm text-slate-500 truncate">{book.author}</p>
          <p className="text-xs text-slate-400 truncate mt-0.5">{book.publisher}</p>
          {book.publishedAt && (
            <p className="text-xs text-slate-300 mt-0.5">{book.publishedAt}</p>
          )}
          <span className="mt-2 inline-block text-xs font-mono bg-orange-50 text-orange-400 px-2 py-0.5 rounded-lg">
            {book.isbn13}
          </span>
        </div>
      </div>
    </div>
  )
}

export function ELibraryStep({ book, onNext, onBack, onReset }: Props) {
  const [libraries, setLibraries] = useState<ELibraryInfo[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [keyword, setKeyword] = useState('')
  const [libLoading, setLibLoading] = useState(true)
  const [searchResult, setSearchResult] = useState<ELibrarySearchResponse | null>(null)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const restoredRef = useRef(false)

  useEffect(() => {
    getELibraries()
      .then(libs => setLibraries(libs))
      .catch(e => setError(e.message))
      .finally(() => setLibLoading(false))
  }, [])

  // Restore saved selection after libraries load
  useEffect(() => {
    if (libraries.length === 0 || restoredRef.current) return
    restoredRef.current = true
    const saved = getSavedIds()
    const valid = new Set(saved.filter(id => libraries.some(l => l.libraryId === id)))
    if (valid.size > 0) setSelected(valid)
  }, [libraries])

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      saveIds([...next])
      return next
    })
  }

  function toggleAll() {
    if (selected.size === libraries.length) {
      setSelected(new Set())
      saveIds([])
    } else {
      const all = new Set(libraries.map(l => l.libraryId))
      setSelected(all)
      saveIds([...all])
    }
  }

  async function handleSearch() {
    if (selected.size === 0 || selected.size > MAX_SELECT) return
    setSearching(true)
    setError(null)
    try {
      const ids = [...selected].join(',')
      const res = await searchELibraries(book.isbn13, ids, book.title || undefined)
      setSearchResult(res)
    } catch (e) {
      setError(e instanceof Error ? e.message : '검색 실패')
    } finally {
      setSearching(false)
    }
  }

  const filteredLibs = keyword
    ? libraries.filter(l =>
        l.name.includes(keyword) || l.region?.includes(keyword) || l.vendorType?.includes(keyword)
      )
    : libraries

  const overLimit = selected.size > MAX_SELECT

  const selectedLibs = libraries.filter(l => selected.has(l.libraryId))

  return (
    <div>
      {/* Nav header */}
      <div className="flex items-center gap-2 mb-4">
        <button
          onClick={onBack}
          className="p-2 rounded-xl hover:bg-orange-50 transition cursor-pointer"
          aria-label="뒤로"
        >
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2.5" strokeLinecap="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <span className="text-[13px] font-medium text-slate-400">전자도서관 검색</span>
      </div>

      {/* Book detail card */}
      <BookDetailCard book={book} />

      {libLoading ? (
        <div className="flex justify-center py-12">
          <svg className="animate-spin text-primary" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
          </svg>
        </div>
      ) : (
        <>
          {/* Library selector */}
          {!searchResult && (
            <div className="mb-4">
              <div className="flex items-center justify-between mb-2">
                <p className="text-[13px] font-medium text-slate-600">
                  도서관 선택
                  <span className={`ml-1.5 ${overLimit ? 'text-red-400' : 'text-slate-300'}`}>
                    {selected.size}/{MAX_SELECT}
                  </span>
                </p>
                <button
                  onClick={toggleAll}
                  className="text-xs text-primary hover:underline cursor-pointer"
                >
                  {selected.size === libraries.length ? '전체 해제' : '전체 선택'}
                </button>
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
                        className="w-4 h-4 rounded-full hover:bg-orange-200 flex items-center justify-center
                          transition cursor-pointer shrink-0"
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

              <div className="max-h-48 overflow-y-auto scrollbar-thin space-y-0.5 bg-white rounded-2xl border border-orange-50 p-1">
                {filteredLibs.length === 0 && (
                  <p className="text-sm text-slate-300 text-center py-4">검색 결과 없음</p>
                )}
                {filteredLibs.map(lib => (
                  <label
                    key={lib.libraryId}
                    className="flex items-center gap-3 px-3 py-2 rounded-xl hover:bg-orange-50/50 cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(lib.libraryId)}
                      onChange={() => toggle(lib.libraryId)}
                      className="w-4 h-4 accent-primary shrink-0"
                    />
                    <span className="text-sm text-slate-700 flex-1 truncate">{lib.name}</span>
                    <span className="text-xs text-slate-300 shrink-0">{lib.region}</span>
                  </label>
                ))}
              </div>
            </div>
          )}

          {error && (
            <div className="p-3 bg-red-50 border border-red-100 rounded-2xl text-red-500 text-sm mb-4">
              {error}
            </div>
          )}

          {!searchResult && (
            <button
              onClick={handleSearch}
              disabled={searching || selected.size === 0 || overLimit}
              className="w-full py-3.5 bg-primary text-white rounded-2xl font-bold text-[15px]
                hover:bg-primary-dark active:scale-[0.98] transition disabled:opacity-40
                disabled:cursor-not-allowed flex items-center justify-center gap-2 cursor-pointer
                shadow-sm shadow-orange-200"
            >
              {searching ? (
                <>
                  <svg className="animate-spin" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                    <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
                  </svg>
                  검색 중…
                </>
              ) : (
                `${selected.size}개 도서관 검색`
              )}
            </button>
          )}

          {/* Results */}
          {searchResult && (
            <div>
              <div className="flex items-center justify-between mb-3">
                <p className="text-[13px] font-medium text-slate-500">
                  검색 완료 · <span className="font-mono">{searchResult.metadata.totalElapsedMs}ms</span>
                </p>
                <button
                  onClick={() => setSearchResult(null)}
                  className="text-xs text-slate-300 hover:text-primary cursor-pointer"
                >
                  다시 선택
                </button>
              </div>

              <div className="space-y-2 max-h-72 overflow-y-auto scrollbar-thin pr-0.5 mb-4">
                {searchResult.results.map(result => (
                  <div key={result.libraryId} className="bg-white border border-orange-50 rounded-2xl p-4">
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-semibold text-slate-800 text-sm">{result.libraryName}</span>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono text-slate-200">{result.elapsedMs}ms</span>
                        <span className={`text-xs font-medium ${statusColor[result.status] ?? 'text-slate-400'}`}>
                          {statusLabel[result.status] ?? result.status}
                        </span>
                      </div>
                    </div>

                    {result.status === 'SUCCESS' && result.books.length === 0 && (
                      <p className="text-xs text-slate-300">소장 도서 없음</p>
                    )}

                    {result.books.map((b, i) => (
                      <div key={i} className="flex items-center gap-3 py-2 border-t border-slate-50 first:border-0">
                        {b.coverUrl && (
                          <img src={b.coverUrl} alt={b.title} className="w-8 h-11 object-cover rounded-lg shadow-sm shrink-0" />
                        )}
                        <div className="min-w-0 flex-1">
                          <a
                            href={b.detailUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-sm font-medium text-slate-700 hover:text-primary hover:underline truncate block"
                          >
                            {b.title}
                          </a>
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

              {searchResult.metadata.failures.length > 0 && (
                <div className="mb-4 p-3 bg-slate-50 rounded-2xl">
                  <p className="text-xs font-medium text-slate-400 mb-1">접속 실패</p>
                  {searchResult.metadata.failures.map((f, i) => (
                    <p key={i} className="text-xs text-slate-300">{f.libraryName ?? `#${f.libraryId}`} — {f.reason}</p>
                  ))}
                </div>
              )}

              {/* Next step CTA */}
              <button
                onClick={onNext}
                className="w-full py-3.5 bg-primary text-white rounded-2xl font-bold text-[15px]
                  hover:bg-primary-dark active:scale-[0.98] transition shadow-sm shadow-orange-200
                  flex items-center justify-center gap-2 cursor-pointer mb-3"
              >
                공공도서관 · 구매 정보 보기
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M9 18l6-6-6-6" />
                </svg>
              </button>

              <button
                onClick={onReset}
                className="w-full py-3 border border-orange-100 rounded-2xl text-sm text-slate-400
                  hover:border-primary hover:text-primary transition cursor-pointer bg-white"
              >
                처음부터 다시 검색
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
