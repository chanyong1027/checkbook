import { useState, useRef } from 'react'
import { searchBooks } from '../api'
import type { BookCandidate } from '../types'
import { Spinner } from './Spinner'

interface Props {
  onSelect: (book: BookCandidate) => void
}

export function BookSearchStep({ onSelect }: Props) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<BookCandidate[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    if (!query.trim()) return
    setLoading(true)
    setError(null)
    setSearched(false)
    try {
      const res = await searchBooks(query.trim())
      setResults(res.items)
      setSearched(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : '검색 실패')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <h2 className="text-2xl font-bold text-slate-900 mb-1">책 검색</h2>
      <p className="text-slate-500 text-sm mb-6">제목, 저자, ISBN으로 검색하세요</p>

      <form onSubmit={handleSearch} className="flex gap-2 mb-6">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="예) 채식주의자, 9788936434267"
          className="flex-1 px-4 py-3 rounded-xl border border-slate-200 bg-white text-slate-900 placeholder:text-slate-400
            focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent transition"
          maxLength={200}
          autoFocus
        />
        <button
          type="submit"
          disabled={loading || !query.trim()}
          className="px-6 py-3 bg-primary text-white rounded-xl font-semibold hover:bg-blue-700 active:scale-95
            transition disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer"
        >
          {loading ? <Spinner size={18} /> : (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" />
            </svg>
          )}
          검색
        </button>
      </form>

      {error && (
        <div className="p-4 bg-red-50 border border-red-100 rounded-xl text-red-600 text-sm mb-4">
          {error}
        </div>
      )}

      {searched && results.length === 0 && (
        <div className="text-center py-12 text-slate-400">
          <svg className="mx-auto mb-3 opacity-40" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M9 3H5a2 2 0 0 0-2 2v4m6-6h10a2 2 0 0 1 2 2v4M9 3v18m0 0h10a2 2 0 0 0 2-2V9M9 21H5a2 2 0 0 1-2-2V9m0 0h18" />
          </svg>
          검색 결과가 없습니다
        </div>
      )}

      {results.length > 0 && (
        <div className="space-y-3 max-h-[520px] overflow-y-auto scrollbar-thin pr-1">
          {results.map(book => (
            <button
              key={book.isbn13}
              onClick={() => onSelect(book)}
              className="w-full flex gap-4 p-4 bg-white rounded-xl border border-slate-100 hover:border-primary
                hover:shadow-md transition text-left cursor-pointer group"
            >
              {book.coverUrl ? (
                <img
                  src={book.coverUrl}
                  alt={book.title}
                  className="w-14 h-20 object-cover rounded-lg shrink-0 shadow-sm"
                />
              ) : (
                <div className="w-14 h-20 bg-slate-100 rounded-lg shrink-0 flex items-center justify-center">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="1.5">
                    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                  </svg>
                </div>
              )}
              <div className="flex-1 min-w-0">
                <p className="font-semibold text-slate-900 truncate group-hover:text-primary transition">{book.title}</p>
                <p className="text-sm text-slate-500 mt-0.5 truncate">{book.author}</p>
                <p className="text-sm text-slate-400 truncate">{book.publisher} · {book.publishedAt}</p>
                <p className="text-xs font-mono text-slate-300 mt-1">{book.isbn13}</p>
              </div>
              <div className="self-center text-slate-300 group-hover:text-primary transition">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M9 18l6-6-6-6" />
                </svg>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
