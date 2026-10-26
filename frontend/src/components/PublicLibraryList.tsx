import { useEffect, useRef, useState } from 'react'
import { getPublicLibraryAvailability } from '../api'
import { ApiError } from '../api/errors.ts'
import { isSafeUrl } from '../utils/url'
import { formatDistance } from '../utils/distance'
import type { PublicLibraryInfo } from '../types'
import { StatusChip, type Tone } from './detail/parts'
import { GlobeIcon, MapPinIcon, NavigateIcon, RefreshIcon, SpinnerIcon } from './shared/icons'

// 하루 한도(앱 429)·공급자 예산(앱 503)·속도 제한(Nginx 429 JSON)
const LIMIT_ERROR_CODES = new Set(['DAILY_REQUEST_LIMIT_EXCEEDED', 'PROVIDER_DAILY_LIMIT', 'RATE_LIMIT_EXCEEDED'])

interface Props {
  isbn13: string
  lat: number | null
  lon: number | null
  initialLibraries: PublicLibraryInfo[]
  initialTotal: number | null
  initialNextOffset: number | null
}

// 지금 빌릴 수 있는 곳이 먼저, 같은 상태끼리는 가까운 순(서버는 거리순으로 준다)
function rank(lib: PublicLibraryInfo): number {
  if (lib.hasBook && lib.loanAvailable) return 0
  if (lib.hasBook) return 1
  return 2
}

function status(lib: PublicLibraryInfo): { tone: Tone; label: string } {
  if (lib.hasBook && lib.loanAvailable) return { tone: 'ok', label: '대출가능' }
  if (lib.hasBook) return { tone: 'off', label: '대출중' }
  return { tone: 'off', label: '미보유' }
}

function limitMessage(err: unknown, fallback: string): string {
  // 한도 초과만 서버가 준 안내 문구를 보여 준다. 상태 코드가 아니라 오류 코드로 가린다 —
  // 앱이 내려가 Nginx·Cloudflare가 HTML 503을 주면 본문 코드가 없어 이 화면의 문구가 맞다
  const limited = err instanceof ApiError && err.code != null && LIMIT_ERROR_CODES.has(err.code)
  return limited ? (err as ApiError).message : fallback
}

function LibraryLinks({ lib }: { lib: PublicLibraryInfo }) {
  const link = 'inline-flex items-center gap-1 min-h-9 text-label font-semibold text-ink-700 hover:text-ink-900 hover:underline underline-offset-2'
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
      {isSafeUrl(lib.homepage) && (
        <a href={lib.homepage} target="_blank" rel="noopener noreferrer" aria-label={`${lib.libraryName} 홈페이지 열기`} className={link}>
          <GlobeIcon size={14} />
          홈페이지
        </a>
      )}
      {lib.latitude != null && lib.longitude != null && (
        <>
          <a
            href={`https://map.kakao.com/link/search/${encodeURIComponent(lib.libraryName)}`}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`${lib.libraryName} 카카오맵에서 보기`}
            className={link}
          >
            <MapPinIcon size={14} />
            장소 정보
          </a>
          <a
            href={`https://map.kakao.com/link/to/${encodeURIComponent(lib.libraryName)},${lib.latitude},${lib.longitude}`}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`${lib.libraryName} 길찾기`}
            className={link}
          >
            <NavigateIcon size={14} />
            길찾기
          </a>
        </>
      )}
    </div>
  )
}

// 부모가 검색마다 key를 바꿔 리마운트하므로 initial* props는 최초 1회 반영으로 충분
export function PublicLibraryList({
  isbn13,
  lat,
  lon,
  initialLibraries,
  initialTotal,
  initialNextOffset,
}: Props) {
  const [libraries, setLibraries] = useState<PublicLibraryInfo[]>(initialLibraries)
  const [total, setTotal] = useState<number | null>(initialTotal)
  const [nextOffset, setNextOffset] = useState<number | null>(initialNextOffset)
  // 백엔드 페이지 크기는 서버 설정값이라 하드코딩하지 않고 응답에서 유추
  // (첫 페이지는 offset 0에서 시작하므로 initialNextOffset이 곧 페이지 크기)
  const [pageSize, setPageSize] = useState<number | null>(initialNextOffset)
  const [busy, setBusy] = useState<'more' | 'retry' | null>(null)
  const [requestError, setRequestError] = useState<string | null>(null)
  const requestAbort = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => {
      requestAbort.current?.abort()
    }
  }, [])

  function request(offset: number, kind: 'more' | 'retry') {
    // busy 가드로 요청은 항상 단일 비행 — 언마운트 abort 는 AbortError catch 로 흡수
    if (busy || lat == null || lon == null) return
    const controller = new AbortController()
    requestAbort.current = controller
    setBusy(kind)
    setRequestError(null)
    getPublicLibraryAvailability(isbn13, lat, lon, offset, controller.signal)
      .then(res => {
        // 다시 시도는 첫 구간부터 새로 받는다(응답 없던 곳이 이번엔 올 수 있다) — 더 본 구간은 다시 더 보기로
        setLibraries(prev => (kind === 'retry' ? res.libraries : [...prev, ...res.libraries]))
        setTotal(res.total)
        setNextOffset(res.nextOffset)
        if (res.nextOffset != null) setPageSize(res.nextOffset - res.offset)
        setBusy(null)
      })
      .catch(err => {
        if (err?.name === 'AbortError') return
        setRequestError(
          limitMessage(err, kind === 'retry' ? '다시 불러오지 못했어요. 잠시 후 다시 시도해주세요.' : '도서관 정보를 더 가져오지 못했어요. 다시 시도해주세요.'),
        )
        setBusy(null)
      })
  }

  // 위치 좌표 없이는 요청 자체가 불가능하므로 버튼도 숨긴다
  const canRequest = lat != null && lon != null
  const hasMore = nextOffset != null && canRequest
  // 지금까지 조회를 시도한 후보 수 — 응답 없던 도서관은 목록에서 빠지므로 차이가 곧 건너뛴 수
  const coveredCount = nextOffset ?? total ?? libraries.length
  const skippedCount = Math.max(0, coveredCount - libraries.length)
  const remaining = total != null && nextOffset != null ? Math.max(0, total - nextOffset) : null
  const nextBatch = remaining != null && pageSize != null ? Math.min(pageSize, remaining) : null

  const sorted = [...libraries].sort((a, b) => rank(a) - rank(b) || a.distance - b.distance)
  const nearestAvailable = sorted[0] && rank(sorted[0]) === 0 ? sorted[0] : null
  const rest = nearestAvailable ? sorted.slice(1) : sorted
  const availableCount = libraries.filter(lib => rank(lib) === 0).length

  return (
    <div>
      {libraries.length > 0 && (
        <p className="mb-3 text-meta text-ink-600">
          가까운 {libraries.length}곳 중 <span className="font-bold text-ink-900">{availableCount}곳 대출가능</span>
          {total != null && total > coveredCount && <span> · 근처 {total}곳 중 {coveredCount}곳 확인</span>}
        </p>
      )}

      {nearestAvailable && (
        <div className="mb-2 rounded-xl border border-status-ok/25 bg-status-ok-soft p-4">
          <p className="text-label font-bold text-status-ok">가장 가까운 대출가능 도서관</p>
          <div className="mt-1 flex items-baseline gap-2 flex-wrap">
            <span className="text-body-lg font-bold text-ink-900">{nearestAvailable.libraryName}</span>
            <span className="text-meta text-ink-600 tabular-nums">{formatDistance(nearestAvailable.distance)}</span>
          </div>
          <p className="text-meta text-ink-600 truncate">{nearestAvailable.address}</p>
          <div className="mt-2">
            <LibraryLinks lib={nearestAvailable} />
          </div>
        </div>
      )}

      {rest.length > 0 && (
        <ul role="list">
          {rest.map((lib, i) => {
            const s = status(lib)
            return (
              <li key={`${lib.libraryName}-${i}`} className="py-3 border-t border-ink-100 first:border-t-0">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-body font-semibold text-ink-900">{lib.libraryName}</p>
                    <p className="text-meta text-ink-600 truncate">
                      <span className="tabular-nums">{formatDistance(lib.distance)}</span> · {lib.address}
                    </p>
                  </div>
                  <StatusChip tone={s.tone}>{s.label}</StatusChip>
                </div>
                <div className="mt-1">
                  <LibraryLinks lib={lib} />
                </div>
              </li>
            )
          })}
        </ul>
      )}

      {libraries.length === 0 && (
        <p className="text-body text-ink-600 py-1">근처 도서관의 응답을 받지 못했어요</p>
      )}

      {skippedCount > 0 && (
        <div className="mt-3 flex items-center justify-between gap-3 rounded-xl border border-dashed border-ink-300 px-4 py-2">
          <span className="text-meta text-ink-700">도서관 {skippedCount}곳은 응답이 없었어요</span>
          {canRequest && (
            <button
              type="button"
              onClick={() => request(0, 'retry')}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 min-h-10 px-2 text-meta font-semibold text-primary hover:text-primary-dark disabled:opacity-50 cursor-pointer"
            >
              {busy === 'retry' ? <SpinnerIcon size={14} strokeWidth={2.2} /> : <RefreshIcon size={14} strokeWidth={2} />}
              다시 시도
            </button>
          )}
        </div>
      )}

      {requestError && <p role="alert" className="mt-2 text-meta text-status-danger">{requestError}</p>}

      {hasMore && (
        <button
          type="button"
          onClick={() => request(nextOffset ?? 0, 'more')}
          disabled={busy !== null}
          aria-busy={busy === 'more'}
          className="mt-3 w-full min-h-11 rounded-xl border border-ink-200 bg-white text-meta font-semibold text-ink-900
            hover:border-ink-400 transition cursor-pointer disabled:cursor-default inline-flex items-center justify-center gap-2"
        >
          {busy === 'more' ? (
            <>
              <SpinnerIcon size={16} strokeWidth={2.2} />
              <span className="sr-only">근처 도서관 불러오는 중</span>
            </>
          ) : (
            `근처 도서관 ${nextBatch != null ? `${nextBatch}곳 ` : ''}더 보기`
          )}
        </button>
      )}
    </div>
  )
}
