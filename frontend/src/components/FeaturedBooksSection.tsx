import { useEffect, useState } from 'react'
import { getFeatured } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type { BookCandidate, FeaturedSectionType } from '../types'

interface Props {
  onSelect: (book: BookCandidate) => void
}

interface TabSpec {
  type: FeaturedSectionType
  label: string
}

const TABS: TabSpec[] = [
  { type: 'BESTSELLER', label: '베스트셀러' },
  { type: 'LOAN',       label: '인기대출' },
  { type: 'NEW',        label: '신간' },
]

function CardSkeleton() {
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

export function FeaturedBooksSection({ onSelect }: Props) {
  const [activeTab, setActiveTab] = useState<FeaturedSectionType>('BESTSELLER')
  const [items, setItems] = useState<BookCandidate[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    setItems([])
    getFeatured(activeTab, controller.signal)
      .then(res => setItems(res.items))
      .catch(err => {
        if (controller.signal.aborted) return
        setError(toUserMessage(err, '추천 도서를 불러오지 못했습니다.'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [activeTab])

  return (
    <section className="mb-5">
      {/* 헤더 + 탭 */}
      <div className="flex items-end justify-between mb-3 gap-3">
        <div>
          <h2 className="text-base font-bold text-slate-800">요즘 많이 찾는 책</h2>
          <p className="text-xs text-slate-400 mt-0.5">사고 빌리는 책을 한눈에</p>
        </div>
        <div className="flex p-0.5 rounded-full bg-white border border-orange-100 shrink-0">
          {TABS.map(tab => (
            <button
              key={tab.type}
              onClick={() => setActiveTab(tab.type)}
              className={
                'px-3 py-1.5 rounded-full text-xs font-semibold transition cursor-pointer ' +
                (activeTab === tab.type
                  ? 'bg-primary text-white'
                  : 'text-slate-500 hover:text-primary')
              }
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* 콘텐츠 */}
      {error && (
        <p className="text-xs text-slate-400 py-6 text-center">{error}</p>
      )}

      {!error && loading && (
        <div className="space-y-2">
          <CardSkeleton />
          <CardSkeleton />
          <CardSkeleton />
        </div>
      )}

      {!error && !loading && items.length === 0 && (
        <p className="text-xs text-slate-400 py-6 text-center">
          잠시 후 다시 시도해주세요
        </p>
      )}

      {!error && !loading && items.length > 0 && (
        <div className="space-y-2">
          {items.map((book, idx) => (
            <button
              key={`${book.isbn13}-${idx}`}
              onClick={() => onSelect(book)}
              className="w-full flex gap-3 p-3.5 bg-white rounded-2xl border border-orange-50
                hover:border-primary/40 hover:shadow-sm active:scale-[0.98] transition text-left
                cursor-pointer group"
            >
              {book.coverUrl ? (
                <img
                  src={book.coverUrl}
                  alt={book.title}
                  className="w-12 h-[68px] object-cover rounded-lg shrink-0 shadow-sm"
                />
              ) : (
                <div className="w-12 h-[68px] bg-orange-50 rounded-lg shrink-0 flex items-center justify-center">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
                    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                  </svg>
                </div>
              )}
              <div className="flex-1 min-w-0 self-center">
                <span className="inline-flex items-center justify-center text-[10px] font-bold
                  px-1.5 py-0.5 rounded-full bg-orange-50 text-primary mb-1">
                  {idx + 1}
                </span>
                <p className="font-semibold text-slate-800 truncate text-[14px] group-hover:text-primary transition leading-snug">
                  {book.title}
                </p>
                <p className="text-xs text-slate-400 mt-0.5 truncate">{book.author}</p>
                <p className="text-xs text-slate-300 truncate">{book.publisher}</p>
              </div>
            </button>
          ))}
        </div>
      )}
    </section>
  )
}
