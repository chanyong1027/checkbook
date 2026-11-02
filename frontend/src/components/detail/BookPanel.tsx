// 책 정보 아래에서 읽는 방법과 다음 행동을 함께 비교한다.
import type { BookCandidate } from '../../types'
import { BookIcon, ChevronRightIcon, ExternalIcon, MapPinIcon, SpinnerIcon } from '../shared/icons'

export type SummaryTone = 'ok' | 'off' | 'neutral' | 'warn' | 'loading'

export interface SummaryItem {
  href: string
  label: string
  value: string
  tone: SummaryTone
  action: string
  note: string
  externalUrl?: string
}

const TILE: Record<SummaryTone, string> = {
  ok: 'bg-status-ok-soft/60 border-status-ok/20',
  off: 'bg-white border-ink-100',
  neutral: 'bg-white border-ink-100',
  warn: 'bg-white border-ink-200',
  loading: 'bg-white border-ink-100',
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
  const size = 'w-16 h-24 md:w-[76px] md:h-28'
  if (book?.coverUrl) {
    return (
      <img
        src={book.coverUrl}
        alt=""
        className={`${size} object-cover rounded shrink-0 ring-1 ring-black/5`}
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
    <section aria-label="책 정보와 읽는 방법 비교">
      <div className="flex items-start gap-4 md:gap-5 py-1">
        {book || bookNotice ? <Cover book={book} /> : <div className="w-16 h-24 md:w-[76px] md:h-28 rounded bg-ink-100 animate-pulse shrink-0" />}
        <div className="min-w-0 flex-1 self-center">
          {book ? (
            <>
              <h1 className="text-heading md:text-heading-lg font-bold text-ink-900">{book.title}</h1>
              <p className="mt-1.5 text-meta md:text-body text-ink-700">
                {[book.author, book.publisher].filter(Boolean).join(' · ')}
              </p>
              <p className="mt-1 text-label text-ink-600 tabular-nums">
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
        <button type="button" onClick={onReset} className="hidden sm:inline-flex min-h-11 shrink-0 items-center gap-1 rounded-xl border border-ink-200 bg-white px-4 text-meta font-semibold text-ink-700 hover:border-ink-400 transition cursor-pointer">
          다른 책 찾기 <ChevronRightIcon size={15} />
        </button>
      </div>

      <div className="mt-6 md:mt-7">
        <div className="flex flex-wrap items-baseline justify-between gap-1 mb-3">
          <h2 className="text-title font-bold text-ink-900">이 책을 읽는 방법</h2>
          <p className="text-label md:text-meta text-ink-600">대출 · 구독 · 구매를 한눈에</p>
        </div>
        <ul role="list" aria-label="읽는 방법 비교" className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 md:gap-3">
          {summary.map(item => {
            const needsLocation = item.href === '#library' && location.needed && !location.granted
            const busy = needsLocation ? location.loading : item.tone === 'loading'
            const actionClass = 'mt-auto min-h-11 inline-flex items-center justify-between gap-1 text-label md:text-meta font-semibold rounded-lg transition cursor-pointer ' +
              (needsLocation ? 'bg-primary text-white px-3 hover:bg-primary-dark' : 'text-ink-900 hover:text-primary')
            return (
              <li key={item.href} className={`flex min-w-0 flex-col rounded-xl border p-3 md:p-4 ${TILE[item.tone]}`}>
                <span className="text-label md:text-meta font-semibold text-ink-600">{item.label}</span>
                <span aria-live="polite" className={`mt-1 flex items-center gap-1.5 text-body md:text-body-lg font-bold ${VALUE[item.tone]}`}>
                  {item.tone === 'loading' && <SpinnerIcon size={14} strokeWidth={2.2} />}
                  {item.value}
                </span>
                <p className="mt-1 mb-2 text-label text-ink-600">{item.note}</p>
                {item.externalUrl ? (
                  <a href={item.externalUrl} target="_blank" rel="noopener noreferrer" aria-label={`${item.action} (새 창)`} className={actionClass}>
                    {item.action}<ExternalIcon size={14} className="shrink-0" />
                  </a>
                ) : (
                  <button type="button" disabled={busy} onClick={() => needsLocation ? location.onRequest() : onNavigate(item.href)} className={`${actionClass} disabled:opacity-50 disabled:cursor-wait`}>
                    {needsLocation && location.loading ? '위치 확인 중' : item.action}
                    {needsLocation ? <MapPinIcon size={15} className="shrink-0" /> : <ChevronRightIcon size={15} className="shrink-0" />}
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      </div>

      {location.needed && !location.granted && (
        <div className="mt-3">
          <p className="text-label text-ink-600">위치는 근처 도서관·매장을 찾는 데만 쓰고 저장하지 않아요.</p>
          {location.error && (
            <p role="alert" className="mt-2 text-label text-status-danger">{location.error}</p>
          )}
        </div>
      )}
      {location.granted && (
        <p className="mt-3 flex items-center gap-1.5 text-label text-ink-600">
          <MapPinIcon size={14} />
          내 위치를 기준으로 찾고 있어요
        </p>
      )}

    </section>
  )
}
