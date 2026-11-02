import { useEffect, useState } from 'react'
import type { PublicLibraryBatchItem } from '../../types'
import { usePreferences } from '../../preferences/PreferencesContext'
import { isSafeUrl } from '../../utils/url'
import { formatDistance } from '../../utils/distance'
import { StatusChip, type Tone } from '../detail/parts'
import type { SummaryItem } from '../detail/BookPanel'
import { summarizeLibraries } from '../detail/summary'
import { GlobeIcon, MapPinIcon, NavigateIcon, RefreshIcon, SpinnerIcon } from '../shared/icons'
import { stopMessage } from './nearbyExplorer.ts'
import { useNearbyExplorer, useSavedLibraries, type LibraryOrigin } from './useLibraryLookups'

type Mode = 'saved' | 'nearby'
type OriginChoice = 'gps' | 'reference'
export type LibrarySummary = Pick<SummaryItem, 'value' | 'tone'>

const REASON_LABEL: Record<string, string> = {
  TIMEOUT: '응답 늦음',
  CIRCUIT_OPEN: '제공처 장애',
  DAILY_LIMIT: '오늘 조회 마감',
  POOL_BUSY: '서버 혼잡',
  INVALID_RESPONSE: '응답 오류',
}

function status(item: PublicLibraryBatchItem): { tone: Tone; label: string } {
  switch (item.state) {
    case 'AVAILABLE': return { tone: 'ok', label: '대출가능' }
    case 'ON_LOAN': return { tone: 'off', label: '대출중' }
    case 'NOT_HELD': return { tone: 'off', label: '미소장' }
    default: return { tone: 'unknown', label: REASON_LABEL[item.reason ?? ''] ?? '확인 못 함' }
  }
}

function LibraryLinks({ item }: { item: PublicLibraryBatchItem }) {
  const link = 'inline-flex items-center gap-1 min-h-9 text-label font-semibold text-ink-700 hover:text-ink-900 hover:underline underline-offset-2'
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
      {isSafeUrl(item.homepage) && (
        <a href={item.homepage ?? undefined} target="_blank" rel="noopener noreferrer" aria-label={`${item.name} 홈페이지 열기`} className={link}>
          <GlobeIcon size={14} />
          홈페이지
        </a>
      )}
      <a
        href={`https://map.kakao.com/link/search/${encodeURIComponent(item.name)}`}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`${item.name} 카카오맵에서 보기`}
        className={link}
      >
        <MapPinIcon size={14} />
        장소 정보
      </a>
      <a
        href={`https://map.kakao.com/link/to/${encodeURIComponent(item.name)},${item.latitude},${item.longitude}`}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`${item.name} 길찾기`}
        className={link}
      >
        <NavigateIcon size={14} />
        길찾기
      </a>
    </div>
  )
}

function LibraryRows({ items, distanceFrom }: { items: PublicLibraryBatchItem[]; distanceFrom: string | null }) {
  return (
    <ul role="list">
      {items.map(item => {
        const s = status(item)
        return (
          <li key={item.libCode} className="py-3 border-t border-ink-100 first:border-t-0">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-body font-semibold text-ink-900">{item.name}</p>
                <p className="text-meta text-ink-600 truncate">
                  {item.distanceKm != null && (
                    <span className="tabular-nums">
                      {distanceFrom ? `${distanceFrom}에서 ` : ''}직선 {formatDistance(item.distanceKm)}
                      {item.address ? ' · ' : ''}
                    </span>
                  )}
                  {item.address}
                </p>
              </div>
              <StatusChip tone={s.tone}>{s.label}</StatusChip>
            </div>
            <div className="mt-1">
              <LibraryLinks item={item} />
            </div>
          </li>
        )
      })}
    </ul>
  )
}

function UnknownList({
  items,
  distanceFrom,
  retrying,
  onRetry,
}: {
  items: PublicLibraryBatchItem[]
  distanceFrom: string | null
  retrying: boolean
  onRetry: () => void
}) {
  if (items.length === 0) return null
  return (
    <details className="mt-3 rounded-xl border border-dashed border-ink-300 px-4 py-2">
      <summary className="min-h-10 flex items-center justify-between gap-3 cursor-pointer text-meta text-ink-700">
        <span>확인 못 한 도서관 {items.length}곳</span>
      </summary>
      <p className="text-label text-ink-600 mb-1">없다는 뜻이 아니라 이번에 소장 여부를 받지 못한 곳이에요</p>
      <LibraryRows items={items} distanceFrom={distanceFrom} />
      <button
        type="button"
        onClick={onRetry}
        disabled={retrying}
        className="mt-1 inline-flex items-center gap-1.5 min-h-10 px-2 -ml-2 text-meta font-semibold text-primary hover:text-primary-dark disabled:opacity-50 cursor-pointer"
      >
        {retrying ? <SpinnerIcon size={14} strokeWidth={2.2} /> : <RefreshIcon size={14} strokeWidth={2} />}
        {items.length > 5 ? '가까운 5곳 다시 확인' : '다시 확인'}
      </button>
    </details>
  )
}

const pill = (active: boolean) =>
  `min-h-10 px-3.5 rounded-full text-meta font-semibold border transition cursor-pointer ${
    active ? 'bg-ink-900 text-white border-ink-900' : 'bg-white text-ink-700 border-ink-200 hover:border-ink-400'
  }`

/**
 * 공공도서관 칸(D7-1). 내 도서관(저장한 곳, 미소장도 표시)과 주변(소장한 가까운 5곳을 찾아 이어 가기) 두 모드.
 * 주변의 기준은 내 위치 또는 설정의 기준 도서관이다. 두 모드를 동시에 부르지 않는다.
 */
export function LibrarySection({
  isbn13,
  location,
  locationLoading,
  onRequestLocation,
  onSummaryChange,
}: {
  isbn13: string
  location: { lat: number; lon: number } | null
  locationLoading: boolean
  onRequestLocation: () => void
  onSummaryChange: (summary: LibrarySummary) => void
}) {
  const { preferences, openSettings } = usePreferences()
  const saved = preferences.publicLibraries
  const reference = preferences.referenceLibrary
  const [mode, setMode] = useState<Mode>(saved.length > 0 ? 'saved' : 'nearby')
  const [originChoice, setOriginChoice] = useState<OriginChoice>(location ? 'gps' : 'reference')

  // 저장한 곳을 다 지우면 주변으로
  useEffect(() => {
    if (saved.length === 0 && mode === 'saved') setMode('nearby')
  }, [saved.length, mode])

  // 위치 허용은 사용자가 누른 것이므로 그 기준으로 바꾼다
  const locationKey = location ? `${location.lat},${location.lon}` : ''
  useEffect(() => {
    if (locationKey) setOriginChoice('gps')
  }, [locationKey])

  const origin: LibraryOrigin | null =
    originChoice === 'gps' && location ? location
      : reference ? { referenceLibCode: reference.libCode }
      : location ? location
      : null
  const originIsGps = origin != null && !('referenceLibCode' in origin)
  const distanceFrom = originIsGps ? '내 위치' : reference?.name ?? null

  const savedLookup = useSavedLibraries(isbn13, saved.map(l => l.libCode), mode === 'saved')
  const nearby = useNearbyExplorer(isbn13, origin, mode === 'nearby')

  // --- 한눈 요약 ---
  let summary: LibrarySummary
  if (mode === 'saved') {
    const items = savedLookup.items ?? []
    summary = savedLookup.error ? { value: '불러오지 못함', tone: 'warn' }
      : savedLookup.loading || !savedLookup.items ? { value: '확인 중', tone: 'loading' }
      : summarizeLibraries(items.filter(i => i.state === 'AVAILABLE' || i.state === 'ON_LOAN'),
          items.filter(i => i.state === 'UNKNOWN').length, '내 도서관엔 없음')
  } else if (!origin) {
    summary = { value: '위치 필요', tone: 'neutral' }
  } else if (nearby.running && nearby.state.found.length === 0) {
    summary = { value: '찾는 중', tone: 'loading' }
  } else {
    summary = summarizeLibraries(nearby.state.found, nearby.state.unknown.length, '가까운 곳엔 없음')
  }
  useEffect(() => {
    onSummaryChange(summary)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [summary.value, summary.tone])

  return (
    <div>
      {saved.length > 0 && (
        <div className="flex gap-2 mb-3" role="group" aria-label="도서관 보기 방식">
          <button type="button" className={pill(mode === 'saved')} aria-pressed={mode === 'saved'} onClick={() => setMode('saved')}>
            내 도서관 {saved.length}
          </button>
          <button type="button" className={pill(mode === 'nearby')} aria-pressed={mode === 'nearby'} onClick={() => setMode('nearby')}>
            주변
          </button>
        </div>
      )}

      {/* 정보나루는 전날 자료이고 대출중만 '불가'로 준다(예약·상호대차 중은 가능으로 옴) — D7-0 */}
      <p className="mb-3 text-label text-ink-600">예약·상호대차 중인 책도 '대출가능'으로 보일 수 있어요</p>

      {mode === 'saved' ? (
        <SavedBody lookup={savedLookup} onEdit={() => openSettings('public')} />
      ) : !origin ? (
        <div>
          <p className="text-body text-ink-700">위치를 허용하거나 기준 도서관을 고르면, 이 책을 가진 가까운 도서관을 찾아 드려요</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={onRequestLocation}
              disabled={locationLoading}
              className="min-h-11 px-5 rounded-xl bg-primary text-white text-meta font-bold hover:bg-primary-dark transition cursor-pointer disabled:opacity-50"
            >
              {locationLoading ? '위치 확인 중…' : '내 위치로 찾기'}
            </button>
            <button
              type="button"
              onClick={() => openSettings('public')}
              className="min-h-11 px-5 rounded-xl border border-ink-200 bg-white text-meta font-semibold text-ink-900 hover:border-ink-400 transition cursor-pointer"
            >
              기준 도서관 고르기
            </button>
          </div>
        </div>
      ) : (
        <div>
          <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-meta text-ink-700">
            <span>
              <strong className="text-ink-900">{originIsGps ? '내 위치' : reference?.name}</strong> 기준 10km 안
            </span>
            {location && reference && (
              <button
                type="button"
                onClick={() => setOriginChoice(originIsGps ? 'reference' : 'gps')}
                className="min-h-10 px-2 -ml-2 font-semibold text-primary hover:underline cursor-pointer"
              >
                {originIsGps ? `${reference.name} 기준으로` : '내 위치 기준으로'}
              </button>
            )}
          </div>
          <NearbyBody explorer={nearby} distanceFrom={distanceFrom} />
        </div>
      )}
    </div>
  )
}

function SavedBody({ lookup, onEdit }: { lookup: ReturnType<typeof useSavedLibraries>; onEdit: () => void }) {
  if (lookup.error) {
    return (
      <div className="flex items-center justify-between gap-3">
        <p role="alert" className="text-meta text-status-danger">{lookup.error}</p>
        <button type="button" onClick={lookup.reload} className="inline-flex items-center gap-1.5 min-h-10 px-2 text-meta font-semibold text-primary cursor-pointer shrink-0">
          <RefreshIcon size={14} strokeWidth={2} />
          다시 시도
        </button>
      </div>
    )
  }
  if (lookup.loading || !lookup.items) {
    return (
      <p className="flex items-center gap-2 text-meta text-ink-600" role="status">
        <SpinnerIcon size={16} strokeWidth={2.2} />내 도서관을 확인하고 있어요
      </p>
    )
  }
  const unknown = lookup.items.filter(i => i.state === 'UNKNOWN').length
  return (
    <div>
      <LibraryRows items={lookup.items} distanceFrom={null} />
      <div className="mt-2 flex flex-wrap gap-x-4">
        {unknown > 0 && (
          <button type="button" onClick={lookup.reload} className="inline-flex items-center gap-1.5 min-h-10 text-meta font-semibold text-primary cursor-pointer">
            <RefreshIcon size={14} strokeWidth={2} />
            확인 못 한 곳 다시 확인
          </button>
        )}
        <button type="button" onClick={onEdit} className="min-h-10 text-meta font-semibold text-ink-700 hover:underline cursor-pointer">
          내 도서관 바꾸기
        </button>
      </div>
    </div>
  )
}

function NearbyBody({
  explorer,
  distanceFrom,
}: {
  explorer: ReturnType<typeof useNearbyExplorer>
  distanceFrom: string | null
}) {
  const { state, running, stop, error, retrying } = explorer
  const checked = state.seen.length
  const message = error ?? stopMessage(stop)
  const canMore = !running && state.nextOffset != null && stop !== 'DAILY_LIMIT'

  return (
    <div>
      <p className="mb-2 text-meta text-ink-600" role="status" aria-live="polite">
        {running ? (
          <span className="inline-flex items-center gap-2">
            <SpinnerIcon size={14} strokeWidth={2.2} />
            가까운 순으로 {checked}곳 확인 중{state.found.length > 0 ? ` · 소장 ${state.found.length}곳` : ''}
          </span>
        ) : checked > 0 ? (
          <>가까운 {checked}곳 확인 · <span className="font-bold text-ink-900">소장 {state.found.length}곳</span></>
        ) : null}
      </p>

      {state.found.length > 0 && <LibraryRows items={state.found} distanceFrom={distanceFrom} />}

      {!running && checked > 0 && state.found.length === 0 && (
        <p className="text-body text-ink-600 py-1">
          {state.unknown.length > 0
            ? `소장이 확인된 곳이 없어요 · ${state.unknown.length}곳 확인 못 함`
            : `이번에 확인한 ${checked}곳에는 소장 도서관이 없어요`}
        </p>
      )}

      {message && <p role={error ? 'alert' : undefined} className="mt-2 text-meta text-ink-700">{message}</p>}

      <UnknownList items={state.unknown} distanceFrom={distanceFrom} retrying={retrying} onRetry={explorer.retryUnknown} />

      {running ? (
        <button
          type="button"
          onClick={explorer.stopNow}
          className="mt-3 w-full min-h-11 rounded-xl border border-ink-200 bg-white text-meta font-semibold text-ink-900 hover:border-ink-400 transition cursor-pointer"
        >
          그만 찾기
        </button>
      ) : canMore ? (
        <button
          type="button"
          onClick={explorer.more}
          className="mt-3 w-full min-h-11 rounded-xl border border-ink-200 bg-white text-meta font-semibold text-ink-900 hover:border-ink-400 transition cursor-pointer"
        >
          소장 도서관 더 찾기
        </button>
      ) : null}
    </div>
  )
}
