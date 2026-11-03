import { useEffect, useRef, useState } from 'react'
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
import { LibraryMap } from './LibraryMap'
import { kakaoMapKey } from './kakaoMaps.ts'
import { toMapPoints } from './mapPoints.ts'

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
    // 칩은 소장 여부만 말한다. 정보나루 '대출가능'은 실측 24%만 맞아 칩 아래 작은 글씨로 내린다(loanNote)
    case 'AVAILABLE':
    case 'ON_LOAN': return { tone: 'ok', label: '소장' }
    case 'NOT_HELD': return { tone: 'off', label: '미소장' }
    default: return { tone: 'unknown', label: REASON_LABEL[item.reason ?? ''] ?? '확인 못 함' }
  }
}

/** 소장한 곳의 대출 정보. 결론(칩·요약)에 쓰지 않고 참고로만 보인다 */
function loanNote(item: PublicLibraryBatchItem): string | null {
  if (item.state === 'AVAILABLE') return '대출가능'
  if (item.state === 'ON_LOAN') return '대출중'
  return null
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

interface RowSelection {
  selectedCode: string | null
  /** 지도가 열려 있을 때만 준다 — 행에 '지도에서 보기'가 생긴다 */
  onShowOnMap?: (code: string) => void
  mappedCodes?: string[] | null
}

function LibraryRows({
  items,
  distanceFrom,
  selection,
  numbered = false,
}: {
  items: PublicLibraryBatchItem[]
  distanceFrom: string | null
  selection?: RowSelection
  /** 지도 마커와 같은 번호를 앞에 붙인다 */
  numbered?: boolean
}) {
  return (
    <ul role="list">
      {items.map((item, index) => {
        const s = status(item)
        const note = loanNote(item)
        const selected = selection?.selectedCode === item.libCode
        return (
          <li
            key={item.libCode}
            id={`library-row-${item.libCode}`}
            className={`py-3 border-t border-ink-100 first:border-t-0 transition-colors ${selected ? 'bg-accent-soft -mx-2 px-2 rounded-lg' : ''}`}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-body font-semibold text-ink-900">
                  {numbered && <span className="mr-1.5 tabular-nums text-ink-500">{index + 1}.</span>}
                  {item.name}
                </p>
                <p className="text-meta text-ink-600 truncate">
                  {item.distanceKm != null && (
                    <span className="tabular-nums">
                      {distanceFrom ? `${distanceFrom}에서 ` : '거리 '}{formatDistance(item.distanceKm)}
                      {item.address ? ' · ' : ''}
                    </span>
                  )}
                  {item.address}
                </p>
              </div>
              {/* 대출 메모는 칩 바로 아래 오른쪽에 둔다 — 주소 줄 아래에 있으면 부가 정보에 섞여 안 보였다. 화면 낭독도 소장 → 대출 순 */}
              <div className="flex shrink-0 flex-col items-end gap-1">
                <StatusChip tone={s.tone}>{s.label}</StatusChip>
                {note && <p className="text-label text-ink-700">{note}</p>}
              </div>
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-4">
              <LibraryLinks item={item} />
              {selection?.onShowOnMap && selection.mappedCodes?.includes(item.libCode) && (
                <button
                  type="button"
                  onClick={() => selection.onShowOnMap?.(item.libCode)}
                  aria-pressed={selected}
                  className="inline-flex items-center min-h-9 text-label font-semibold text-primary hover:underline underline-offset-2 cursor-pointer"
                >
                  지도에서 보기
                </button>
              )}
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
  disabled,
  onRetry,
}: {
  items: PublicLibraryBatchItem[]
  distanceFrom: string | null
  retrying: boolean
  /** 탐색 중에는 끈다 — 탐색 결과가 다시 확인 결과를 덮어쓰지 않게 */
  disabled: boolean
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
        disabled={retrying || disabled}
        className="mt-1 inline-flex items-center gap-1.5 min-h-10 px-2 -ml-2 text-meta font-semibold text-primary hover:text-primary-dark disabled:opacity-50 cursor-pointer"
      >
        {retrying ? <SpinnerIcon size={14} strokeWidth={2.2} /> : <RefreshIcon size={14} strokeWidth={2} />}
        {items.length > 5 ? '가까운 5곳 다시 확인' : '다시 확인'}
      </button>
    </details>
  )
}

const DESKTOP_QUERY = '(min-width: 1024px)'

/**
 * 지도 영역. 넓은 화면은 처음부터 펼치고, 좁은 화면은 목록을 먼저 보이고 '지도 보기'로 펼친다.
 * 펼쳐야 SDK를 불러온다(홈·첫 검색에서 미리 받지 않음). 키가 없으면 지도 자체를 감춘다.
 */
function MapPanel({
  items,
  origin,
  selectedCode,
  onSelect,
  onOpenChange,
  panRequest,
  viewKey,
}: {
  items: PublicLibraryBatchItem[]
  origin: { lat: number; lng: number; label: string } | null
  selectedCode: string | null
  onSelect: (code: string) => void
  /** 지도가 보이면 지도에 찍힌 기관 코드, 안 보이면 null */
  onOpenChange: (mappedCodes: string[] | null) => void
  panRequest: number
  viewKey: string
}) {
  const [open, setOpen] = useState(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(DESKTOP_QUERY).matches)
  const points = toMapPoints(items)
  const [mapReady, setMapReady] = useState(false)
  const available = kakaoMapKey() != null && (points.length > 0 || origin != null)
  // 지도가 실제로 보일 때만 목록에 '지도에서 보기'를 단다
  const mappedKey = points.map(p => p.code).join(',')
  useEffect(() => onOpenChange(open && available && mapReady ? mappedKey.split(',').filter(Boolean) : null),
    [open, available, mapReady, mappedKey, onOpenChange])
  // 패널이 사라지면(기준점이 없어지는 등) 지도도 닫힌 것으로 알린다
  useEffect(() => () => onOpenChange(null), [onOpenChange])
  if (!available) return null
  return (
    <div className="mb-3">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="mb-2 min-h-10 px-3 -ml-3 text-meta font-semibold text-ink-700 hover:text-ink-900 cursor-pointer"
      >
        {open ? '지도 접기' : '지도 보기'}
      </button>
      {open && (
        <LibraryMap
          points={points}
          origin={origin}
          selectedCode={selectedCode}
          onSelect={onSelect}
          onReadyChange={setMapReady}
          panRequest={panRequest}
          viewKey={viewKey}
        />
      )}
    </div>
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

  // 지도와 목록이 같은 기관을 가리키게 한다. 마커를 누르면 목록 행을, 행에서 누르면 마커를 강조한다
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  // 지도가 보일 때 거기 찍힌 기관 코드(좌표가 이상해 지도에서 뺀 곳엔 '지도에서 보기'를 달지 않는다)
  const [mappedCodes, setMappedCodes] = useState<string[] | null>(null)
  useEffect(() => setSelectedCode(null), [mode, isbn13])
  function selectFromMap(code: string) {
    setSelectedCode(code)
    document.getElementById(`library-row-${code}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
  // 같은 도서관을 다시 눌러도 지도가 그리로 옮기게, 선택과 따로 '옮겨 달라'는 요청 번호를 둔다
  const [panRequest, setPanRequest] = useState(0)
  function showOnMap(code: string) {
    setSelectedCode(code)
    setPanRequest(n => n + 1)
  }
  const selection: RowSelection = { selectedCode, onShowOnMap: mappedCodes ? showOnMap : undefined, mappedCodes }
  // 주변 탐색 기준(내 위치·기준 도서관). 바뀌면 지도의 사용자 조작 상태를 지워 새 결과에 맞추게 한다
  const viewKey = !origin ? '' : 'referenceLibCode' in origin ? `ref:${origin.referenceLibCode}` : `gps:${origin.lat},${origin.lon}`
  const mapItems = mode === 'saved' ? savedLookup.items ?? [] : nearby.state.found
  const mapOrigin = mode === 'nearby' && originIsGps && origin && !('referenceLibCode' in origin)
    ? { lat: origin.lat, lng: origin.lon, label: '내 위치' }
    : null

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
  } else if (nearby.state.seen.length === 0) {
    // 아직 하나도 확인하지 못했으면 '없음'으로 단정하지 않는다
    summary = nearby.error ? { value: '불러오지 못함', tone: 'warn' }
      : nearby.running || nearby.stop == null ? { value: '찾는 중', tone: 'loading' }
      : nearby.stop === 'EXHAUSTED' ? { value: '10km 안 도서관 없음', tone: 'off' }
      : { value: '확인 전', tone: 'neutral' }
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

      {/* 정보나루는 전날 자료이고 대출중만 '불가'로 준다(예약·상호대차 중은 가능으로 옴, D7-0). 11-03 실측에서 '대출가능' 적중 24% */}
      <p className="mb-3 text-label text-ink-600">대출 정보는 실제와 다를 수 있어요. 가기 전에 도서관에서 확인해 보세요</p>

      {(mode === 'saved' || origin) && (
        // 책·모드가 바뀌면 지도를 새로 그린다(사용자가 옮긴 상태를 넘기지 않게, SDK는 이미 받은 것을 쓴다)
        <MapPanel key={`${mode}|${isbn13}`} items={mapItems} origin={mapOrigin} selectedCode={selectedCode} onSelect={selectFromMap} onOpenChange={setMappedCodes}
          panRequest={panRequest} viewKey={viewKey} />
      )}

      {mode === 'saved' ? (
        <SavedBody lookup={savedLookup} selection={selection} onEdit={() => openSettings('public')} />
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
          <p className="mt-2 text-label text-ink-600">위치는 근처 도서관·매장을 찾는 데만 쓰고 저장하지 않아요.</p>
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
          <NearbyBody explorer={nearby} distanceFrom={distanceFrom} selection={selection} />
        </div>
      )}
    </div>
  )
}

function SavedBody({
  lookup,
  selection,
  onEdit,
}: {
  lookup: ReturnType<typeof useSavedLibraries>
  selection: RowSelection
  onEdit: () => void
}) {
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
      <LibraryRows items={lookup.items} distanceFrom={null} selection={selection} numbered />
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
  selection,
}: {
  explorer: ReturnType<typeof useNearbyExplorer>
  distanceFrom: string | null
  selection: RowSelection
}) {
  const { state, running, stop, error, fatal, retrying } = explorer
  const checked = state.seen.length
  const message = error ?? stopMessage(stop)
  // 다시 확인 중에 더 찾으면 세대가 바뀌어 다시 확인 결과가 버려진다 — 끝날 때까지 기다린다
  const canMore = !running && !retrying && state.nextOffset != null && stop !== 'DAILY_LIMIT' && !fatal
  const statusRef = useRef<HTMLDivElement>(null)

  function stopSearching() {
    explorer.stopNow()
    // 누른 버튼이 사라질 수 있어 포커스를 상태 문구로 옮긴다
    statusRef.current?.focus()
  }

  return (
    <div>
      <div ref={statusRef} tabIndex={-1} role="status" aria-live="polite" className="mb-2 text-meta text-ink-600 focus:outline-none">
        <p>
          {running ? (
            <span className="inline-flex items-center gap-2">
              <SpinnerIcon size={14} strokeWidth={2.2} />
              가까운 순으로 {checked}곳 확인 중{state.found.length > 0 ? ` · 소장 ${state.found.length}곳` : ''}
            </span>
          ) : checked > 0 ? (
            <>가까운 {checked}곳 확인 · <span className="font-bold text-ink-900">소장 {state.found.length}곳</span></>
          ) : null}
        </p>
        {/* 멈춘 이유도 같은 알림 영역에 둬 화면 낭독기가 읽게 한다 */}
        {message && <p className={`mt-1 ${error ? 'text-status-danger' : 'text-ink-700'}`}>{message}</p>}
      </div>

      {state.found.length > 0 && <LibraryRows items={state.found} distanceFrom={distanceFrom} selection={selection} numbered />}

      {!running && checked > 0 && state.found.length === 0 && (
        <p className="text-body text-ink-600 py-1">
          {state.unknown.length > 0
            ? `소장이 확인된 곳이 없어요 · ${state.unknown.length}곳 확인 못 함`
            : `이번에 확인한 ${checked}곳에는 소장 도서관이 없어요`}
        </p>
      )}

      <UnknownList items={state.unknown} distanceFrom={distanceFrom} retrying={retrying} disabled={running} onRetry={explorer.retryUnknown} />

      {running ? (
        <button
          type="button"
          onClick={stopSearching}
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
