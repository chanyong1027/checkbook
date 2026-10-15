import { useEffect, useState } from 'react'
import { getFeatured } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type { BookCandidate, FeaturedSectionType, FeaturedSource } from '../types'

interface Props {
  onSelect: (book: BookCandidate) => void
}

interface TabSpec {
  type: FeaturedSectionType
  label: string
}

// YES24 약관: 제공받은 도서 정보를 표시하는 화면에 출처를 적는다(상품 링크는 상세 화면의 새 책 칸)
const SOURCE_LABELS: Record<FeaturedSource, string> = {
  YES24: 'YES24',
  DATANARU: '도서관 정보나루',
  ALADIN: '알라딘',
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
  const [source, setSource] = useState<FeaturedSource | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    setItems([])
    setSource(null)
    getFeatured(activeTab, controller.signal)
      .then(res => {
        setItems(res.items)
        setSource(res.source)
      })
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
          <p className="text-xs text-slate-600 mt-0.5">사고 빌리는 책을 한눈에</p>
        </div>
        <div className="flex p-0.5 rounded-full bg-white border border-orange-100 shrink-0">
          {TABS.map(tab => (
            <button
              key={tab.type}
              onClick={() => setActiveTab(tab.type)}
              className={
                'px-3 py-2 rounded-full text-xs font-semibold transition cursor-pointer ' +
                (activeTab === tab.type
                  ? 'bg-primary text-white'
                  : 'text-slate-600 hover:text-primary')
              }
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* 콘텐츠 */}
      {error && (
        <p className="text-xs text-slate-600 py-6 text-center">{error}</p>
      )}

      {!error && loading && (
        <div className="space-y-2">
          <CardSkeleton />
          <CardSkeleton />
          <CardSkeleton />
        </div>
      )}

      {!error && !loading && items.length === 0 && (
        <p className="text-xs text-slate-600 py-6 text-center">
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
                <p className="font-semibold text-slate-800 truncate text-sm group-hover:text-primary transition leading-snug">
                  {book.title}
                </p>
                <p className="text-xs text-slate-600 mt-0.5 truncate">{book.author}</p>
                <p className="text-xs text-slate-500 truncate">{book.publisher}</p>
              </div>
            </button>
          ))}
        </div>
      )}

      {/* 서버가 새 출처를 보내도(타입에 없는 값) 화면이 깨지지 않게 아는 출처만 적는다 */}
      {!error && !loading && items.length > 0 && source && SOURCE_LABELS[source] && (
        <p className="text-[11px] text-slate-500 mt-2 text-right">
          정보 제공: {SOURCE_LABELS[source]}
        </p>
      )}
    </section>
  )
}
