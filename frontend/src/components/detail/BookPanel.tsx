// 상세 화면의 책 패널: 표지·서지, 한눈 요약, 위치 허용(한 곳에서만), 다른 책 찾기.
// PC(lg)에서는 왼쪽에 붙어 스크롤해도 남고, 모바일·태블릿에서는 맨 위에 온다
import type { BookCandidate } from '../../types'
import { BookIcon, MapPinIcon, SpinnerIcon } from '../shared/icons'

export type SummaryTone = 'ok' | 'off' | 'neutral' | 'warn' | 'loading'

export interface SummaryItem {
  href: string
  label: string
  value: string
  tone: SummaryTone
}

const TILE: Record<SummaryTone, string> = {
  ok: 'bg-status-ok-soft border-status-ok/20',
  off: 'bg-ink-50 border-ink-100',
  neutral: 'bg-ink-50 border-ink-100',
  warn: 'bg-status-danger-soft border-status-danger/20',
  loading: 'bg-ink-50 border-ink-100',
}

const VALUE: Record<SummaryTone, string> = {
  ok: 'text-status-ok',
  off: 'text-ink-700',
  neutral: 'text-ink-900',
  warn: 'text-status-danger',
  loading: 'text-ink-600',
}

interface Props {
  isbn13: string
  book: BookCandidate | null
  bookNotice: string | null
  summary: SummaryItem[]
  location: {
    granted: boolean
    loading: boolean
    error: string | null
    needed: boolean
    onRequest: () => void
  }
  onReset: () => void
  // 요약 항목을 눌렀을 때. 해시 링크로 두면 같은 문서 안 이동도 popstate를 내 화면이 다시 그려진다
  onNavigate: (href: string) => void
}

function Cover({ book }: { book: BookCandidate | null }) {
  const size = 'w-24 h-[140px] lg:w-36 lg:h-[208px]'
  if (book?.coverUrl) {
    return (
      <img
        src={book.coverUrl}
        alt=""
        className={`${size} object-cover rounded shrink-0 ring-1 ring-black/5 shadow-[0_8px_24px_rgba(25,31,40,0.14)]`}
      />
    )
  }
  return (
    <div className={`${size} rounded bg-ink-50 shrink-0 flex items-center justify-center text-ink-300`}>
      <BookIcon size={28} />
    </div>
  )
}

export function BookPanel({ isbn13, book, bookNotice, summary, location, onReset, onNavigate }: Props) {
  const year = book?.publishedAt?.match(/^(\d{4})/)?.[1]

  return (
    <section
      aria-label="책 정보와 한눈 요약"
      // PC에서는 화면보다 길어지면 패널 안에서 스크롤 — 고정된 채 아래 버튼에 못 닿는 일이 없게
      className="bg-white px-4 py-5 md:p-6 md:rounded-2xl md:border md:border-ink-100
        lg:sticky lg:top-24 lg:max-h-[calc(100dvh-7rem)] lg:overflow-y-auto"
    >
      <div className="flex gap-4 lg:flex-col lg:gap-5">
        {book || bookNotice ? <Cover book={book} /> : <div className="w-24 h-[140px] lg:w-36 lg:h-[208px] rounded bg-ink-100 animate-pulse shrink-0" />}
        <div className="min-w-0 flex-1 self-center lg:self-auto">
          {book ? (
            <>
              <h1 className="text-heading lg:text-heading-lg font-bold text-ink-900 line-clamp-3">{book.title}</h1>
              <p className="mt-1.5 text-body text-ink-700">
                {[book.author, book.publisher].filter(Boolean).join(' · ')}
              </p>
              <p className="mt-0.5 text-meta text-ink-500 tabular-nums">
                {[year, `ISBN ${book.isbn13}`].filter(Boolean).join(' · ')}
              </p>
            </>
          ) : bookNotice ? (
            <>
              <h1 className="text-body-lg font-semibold text-ink-700">{bookNotice}</h1>
              <p className="mt-1 text-meta text-ink-500 tabular-nums">ISBN {isbn13}</p>
            </>
          ) : (
            <div className="space-y-2 animate-pulse">
              <h1 className="sr-only">책 정보를 불러오는 중</h1>
              <div className="h-6 bg-ink-100 rounded w-4/5" aria-hidden="true" />
              <div className="h-4 bg-ink-100 rounded w-1/2" />
              <div className="h-4 bg-ink-100 rounded w-2/5" />
            </div>
          )}
        </div>
      </div>

      <div className="mt-5 lg:pt-5 lg:border-t lg:border-ink-100">
        <h2 className="text-meta font-bold text-ink-700 mb-2">한눈에 보기</h2>
        <ul role="list" className="grid grid-cols-2 gap-2 lg:grid-cols-1 lg:gap-0">
          {summary.map(item => (
            <li key={item.href} className="lg:border-t lg:border-ink-100 lg:first:border-t-0">
              <a
                href={item.href}
                onClick={e => {
                  e.preventDefault()
                  onNavigate(item.href)
                }}
                className={
                  'flex flex-col gap-0.5 h-full rounded-xl border p-3 transition hover:border-ink-300 ' +
                  'lg:flex-row lg:items-center lg:justify-between lg:gap-3 lg:rounded-none lg:border-0 lg:bg-transparent lg:px-0 lg:py-2.5 ' +
                  TILE[item.tone]
                }
              >
                <span className="text-label lg:text-meta font-semibold text-ink-600">{item.label}</span>
                <span className={`flex items-center gap-1.5 text-meta lg:text-body font-bold ${VALUE[item.tone]}`}>
                  {item.tone === 'loading' && <SpinnerIcon size={14} strokeWidth={2.2} />}
                  {item.value}
                </span>
              </a>
            </li>
          ))}
        </ul>
      </div>

      {/* 위치는 이 한 곳에서만 묻는다 — 도서관·매장 칸이 같은 위치를 쓴다 */}
      {location.needed && !location.granted && (
        <div className="mt-4 rounded-xl bg-accent-soft p-4">
          <p className="text-meta text-ink-900">
            <span className="font-bold">근처 도서관·매장</span>을 보려면 위치가 필요해요. 검색에만 쓰고 저장하지 않아요.
          </p>
          <button
            type="button"
            onClick={location.onRequest}
            disabled={location.loading}
            className="mt-3 w-full min-h-11 rounded-xl bg-primary text-white text-meta font-bold inline-flex items-center justify-center gap-2
              hover:bg-primary-dark transition disabled:opacity-50 cursor-pointer"
          >
            {location.loading ? <SpinnerIcon size={16} strokeWidth={2.2} /> : <MapPinIcon size={16} strokeWidth={2} />}
            {location.loading ? '위치 확인 중' : '위치 허용하기'}
          </button>
          {location.error && (
            <p role="alert" className="mt-2 text-label text-status-danger">{location.error}</p>
          )}
        </div>
      )}
      {location.granted && (
        <p className="mt-4 flex items-center gap-1.5 text-label text-ink-600">
          <MapPinIcon size={14} />
          내 위치를 기준으로 찾고 있어요
        </p>
      )}

      <button
        type="button"
        onClick={onReset}
        className="mt-5 w-full min-h-11 rounded-xl border border-ink-200 bg-white text-meta font-semibold text-ink-900
          hover:border-ink-400 transition cursor-pointer"
      >
        다른 책 찾기
      </button>
    </section>
  )
}
