import { useState, useEffect } from 'react'
import { getELibraries, searchELibraries } from '../api'
import type { ELibraryInfo, ELibrarySearchResponse } from '../types'
import { Spinner } from './Spinner'

interface Props {
  isbn13: string
  bookTitle: string
  onBack: () => void
  onReset: () => void
}

export function ELibraryStep({ isbn13, bookTitle, onBack, onReset }: Props) {
  const [libraries, setLibraries] = useState<ELibraryInfo[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [keyword, setKeyword] = useState('')
  const [libLoading, setLibLoading] = useState(true)
  const [searchResult, setSearchResult] = useState<ELibrarySearchResponse | null>(null)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getELibraries()
      .then(libs => {
        setLibraries(libs)
      })
      .catch(e => setError(e.message))
      .finally(() => setLibLoading(false))
  }, [])

  function toggleAll() {
    if (selected.size === libraries.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(libraries.map(l => l.libraryId)))
    }
  }

  function toggle(id: number) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function handleSearch() {
    if (selected.size === 0) return
    setSearching(true)
    setError(null)
    try {
      const ids = [...selected].join(',')
      const res = await searchELibraries(isbn13, ids, bookTitle || undefined)
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

  const statusColor: Record<string, string> = {
    SUCCESS: 'text-green-600',
    FAILED: 'text-red-500',
    TIMEOUT: 'text-orange-500',
  }

  const statusLabel: Record<string, string> = {
    SUCCESS: '완료',
    FAILED: '실패',
    TIMEOUT: '타임아웃',
  }

  return (
    <div>
      <div className="flex items-center gap-3 mb-6">
        <button onClick={onBack} className="p-2 rounded-lg hover:bg-slate-100 transition cursor-pointer" aria-label="뒤로">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#64748b" strokeWidth="2.5" strokeLinecap="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <div>
          <h2 className="text-lg font-bold text-slate-900">전자도서관 검색</h2>
          <p className="text-sm text-slate-500 truncate max-w-xs">{bookTitle}</p>
        </div>
      </div>

      {libLoading ? (
        <div className="flex justify-center py-12"><Spinner size={28} /></div>
      ) : (
        <>
          {/* Library selector */}
          {!searchResult && (
            <div className="mb-4">
              <div className="flex items-center justify-between mb-2">
                <p className="text-sm font-medium text-slate-700">
                  검색할 전자도서관 선택
                  <span className={`ml-1 ${selected.size > 20 ? 'text-red-500' : 'text-slate-400'}`}>
                    ({selected.size}/20)
                  </span>
                </p>
                <button onClick={toggleAll} className="text-xs text-primary hover:underline cursor-pointer">
                  {selected.size === libraries.length ? '전체 해제' : '전체 선택'}
                </button>
              </div>
              {selected.size > 20 && (
                <p className="text-xs text-red-500 mb-2">최대 20개까지 선택 가능합니다</p>
              )}

              <input
                type="text"
                placeholder="이름/지역으로 필터"
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                className="w-full px-3 py-2 mb-2 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
              />

              <div className="max-h-56 overflow-y-auto scrollbar-thin space-y-1 pr-1">
                {filteredLibs.map(lib => (
                  <label key={lib.libraryId} className="flex items-center gap-3 p-2.5 rounded-lg hover:bg-slate-50 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={selected.has(lib.libraryId)}
                      onChange={() => toggle(lib.libraryId)}
                      className="w-4 h-4 accent-primary"
                    />
                    <span className="text-sm text-slate-700 flex-1">{lib.name}</span>
                    <span className="text-xs text-slate-400">{lib.region}</span>
                    <span className="text-xs font-mono text-slate-300">{lib.vendorType}</span>
                  </label>
                ))}
              </div>
            </div>
          )}

          {error && (
            <div className="p-3 bg-red-50 border border-red-100 rounded-xl text-red-600 text-sm mb-4">{error}</div>
          )}

          {!searchResult && (
            <button
              onClick={handleSearch}
              disabled={searching || selected.size === 0 || selected.size > 20}
              className="w-full py-3 bg-primary text-white rounded-xl font-semibold hover:bg-blue-700 active:scale-95
                transition disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 cursor-pointer"
            >
              {searching ? <><Spinner size={18} /> 검색 중 (최대 20초)…</> : `${selected.size}개 도서관에서 검색`}
            </button>
          )}

          {/* Results */}
          {searchResult && (
            <div>
              <div className="flex items-center justify-between mb-3">
                <p className="text-sm font-medium text-slate-700">
                  검색 완료 · {searchResult.metadata.totalElapsedMs}ms
                </p>
                <button onClick={() => setSearchResult(null)} className="text-xs text-slate-400 hover:text-primary cursor-pointer">
                  다시 선택
                </button>
              </div>

              <div className="space-y-2 max-h-[440px] overflow-y-auto scrollbar-thin pr-1">
                {searchResult.results.map(result => (
                  <div key={result.libraryId} className="bg-white border border-slate-100 rounded-xl p-4">
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-semibold text-slate-800 text-sm">{result.libraryName}</span>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-slate-300">{result.elapsedMs}ms</span>
                        <span className={`text-xs font-medium ${statusColor[result.status] ?? 'text-slate-400'}`}>
                          {statusLabel[result.status] ?? result.status}
                        </span>
                      </div>
                    </div>

                    {result.status === 'SUCCESS' && result.books.length === 0 && (
                      <p className="text-xs text-slate-400">소장 도서 없음</p>
                    )}

                    {result.books.map((book, i) => (
                      <div key={i} className="flex items-center gap-3 py-2 border-t border-slate-50 first:border-0">
                        {book.coverUrl && (
                          <img src={book.coverUrl} alt={book.title} className="w-9 h-12 object-cover rounded shadow-sm shrink-0" />
                        )}
                        <div className="min-w-0 flex-1">
                          <a href={book.detailUrl} target="_blank" rel="noopener noreferrer"
                            className="text-sm font-medium text-slate-700 hover:text-primary hover:underline truncate block">
                            {book.title}
                          </a>
                          <p className="text-xs text-slate-400">{book.author}</p>
                        </div>
                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium shrink-0 ${book.available ? 'bg-green-50 text-green-700' : 'bg-slate-100 text-slate-400'}`}>
                          {book.available ? '대출가능' : '대출중'}
                        </span>
                      </div>
                    ))}
                  </div>
                ))}
              </div>

              {searchResult.metadata.failures.length > 0 && (
                <div className="mt-3 p-3 bg-slate-50 rounded-xl">
                  <p className="text-xs font-medium text-slate-500 mb-1">제외된 도서관</p>
                  {searchResult.metadata.failures.map((f, i) => (
                    <p key={i} className="text-xs text-slate-400">{f.libraryName ?? `#${f.libraryId}`} — {f.reason}</p>
                  ))}
                </div>
              )}

              <button
                onClick={onReset}
                className="mt-4 w-full py-2.5 border border-slate-200 rounded-xl text-sm text-slate-600
                  hover:border-primary hover:text-primary transition cursor-pointer"
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
