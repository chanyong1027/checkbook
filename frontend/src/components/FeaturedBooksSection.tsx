import { useEffect, useState } from 'react'
import { getFeatured } from '../api'
import { toUserMessage } from '../api/errors.ts'
import type { BookCandidate, FeaturedSectionType, FeaturedSource } from '../types'
import { BookIcon } from './shared/icons'

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
    <div className="animate-pulse" aria-hidden="true">
      <div className="aspect-[2/3] rounded-md bg-ink-100" />
      <div className="mt-2 h-3.5 bg-ink-100 rounded w-5/6" />
      <div className="mt-1.5 h-3 bg-ink-100 rounded w-1/2" />
    </div>
  )
}

// 모바일 3열 → PC 6열. 제목 칸은 2줄 높이로 고정해 한 줄·두 줄 제목이 섞여도 저자 줄 높이가 맞는다
const GRID = 'grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 gap-x-3 gap-y-6 md:gap-x-5 md:gap-y-8'

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
    <section aria-labelledby="featured-title">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between mb-5">
        <div>
          <h2 id="featured-title" className="text-title md:text-heading font-bold text-ink-900">요즘 많이 찾는 책</h2>
          <p className="mt-1 text-meta text-ink-600">사고 빌리는 책을 한눈에</p>
        </div>
        <div role="group" aria-label="추천 목록" className="flex gap-2">
          {TABS.map(tab => {
            const selected = activeTab === tab.type
            return (
              <button
                key={tab.type}
                aria-pressed={selected}
                onClick={() => setActiveTab(tab.type)}
                className={
                  'min-h-10 px-4 rounded-full text-meta font-semibold transition cursor-pointer border ' +
                  (selected
                    ? 'bg-ink-900 border-ink-900 text-white'
                    : 'bg-white border-ink-200 text-ink-700 hover:border-ink-500 hover:text-ink-900')
                }
              >
                {tab.label}
              </button>
            )
          })}
        </div>
      </div>

      {error && <p className="text-meta text-ink-600 py-10 text-center">{error}</p>}

      {!error && loading && (
        <div className={GRID}>
          {Array.from({ length: 12 }, (_, i) => <CardSkeleton key={i} />)}
        </div>
      )}

      {!error && !loading && items.length === 0 && (
        <p className="text-meta text-ink-600 py-10 text-center">잠시 후 다시 시도해주세요</p>
      )}

      {!error && !loading && items.length > 0 && (
        <ol role="list" className={GRID}>
          {items.map((book, idx) => (
            <li key={`${book.isbn13}-${idx}`}>
              <button
                onClick={() => onSelect(book)}
                className="w-full text-left cursor-pointer group"
              >
                <div className="relative aspect-[2/3] rounded-md overflow-hidden bg-ink-50 ring-1 ring-black/5
                  shadow-[0_4px_14px_rgba(25,31,40,0.10)] transition group-hover:-translate-y-0.5 group-hover:shadow-[0_8px_20px_rgba(25,31,40,0.14)]">
                  {book.coverUrl ? (
                    <img src={book.coverUrl} alt="" loading="lazy" className="w-full h-full object-cover" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-ink-300">
                      <BookIcon size={24} />
                    </div>
                  )}
                  <span className="absolute left-0 top-0 min-w-[24px] h-6 px-1.5 bg-ink-900 text-white text-label font-bold
                    flex items-center justify-center">
                    {idx + 1}
                  </span>
                </div>
                <p className="mt-2 text-meta md:text-body font-semibold text-ink-900 line-clamp-2 min-h-[2.5rem] md:min-h-[3rem]
                  group-hover:underline underline-offset-2">
                  {book.title}
                </p>
                <p className="mt-0.5 text-label text-ink-600 truncate">{book.author}</p>
              </button>
            </li>
          ))}
        </ol>
      )}

      {/* 서버가 새 출처를 보내도(타입에 없는 값) 화면이 깨지지 않게 아는 출처만 적는다 */}
      {!error && !loading && items.length > 0 && source && SOURCE_LABELS[source] && (
        <p className="text-caption text-ink-600 mt-6 text-right">
          정보 제공: {SOURCE_LABELS[source]}
        </p>
      )}
    </section>
  )
}
