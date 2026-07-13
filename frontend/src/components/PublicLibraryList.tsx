import { useEffect, useRef, useState } from 'react'
import { getPublicLibraryAvailability } from '../api'
import { isSafeUrl } from '../utils/url'
import type { PublicLibraryInfo } from '../types'

interface Props {
  isbn13: string
  lat: number | null
  lon: number | null
  initialLibraries: PublicLibraryInfo[]
  initialTotal: number | null
  initialNextOffset: number | null
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
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreError, setMoreError] = useState<string | null>(null)
  const requestAbort = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => {
      requestAbort.current?.abort()
    }
  }, [])

  function loadMore() {
    // loadingMore 가드로 요청은 항상 단일 비행 — 언마운트 abort 는 AbortError catch 로 흡수
    if (loadingMore || nextOffset == null || lat == null || lon == null) return
    const controller = new AbortController()
    requestAbort.current = controller
    setLoadingMore(true)
    setMoreError(null)
    getPublicLibraryAvailability(isbn13, lat, lon, nextOffset, controller.signal)
      .then(res => {
        setLibraries(prev => [...prev, ...res.libraries])
        setTotal(res.total)
        setNextOffset(res.nextOffset)
        if (res.nextOffset != null) setPageSize(res.nextOffset - res.offset)
        setLoadingMore(false)
      })
      .catch(err => {
        if (err?.name === 'AbortError') return
        setMoreError('도서관 정보를 더 가져오지 못했습니다. 다시 시도해주세요.')
        setLoadingMore(false)
      })
  }

  // 위치 좌표 없이는 더보기 요청 자체가 불가능하므로 버튼도 숨긴다
  const hasMore = nextOffset != null && lat != null && lon != null
  // 지금까지 조회를 시도한 후보 수 — 응답 없던 도서관은 목록에서 빠지므로 차이가 곧 건너뛴 수
  const coveredCount = nextOffset ?? total ?? libraries.length
  const skippedCount = Math.max(0, coveredCount - libraries.length)
  const remaining = total != null && nextOffset != null ? Math.max(0, total - nextOffset) : null
  const nextBatch =
    remaining != null && pageSize != null ? Math.min(pageSize, remaining) : null

  return (
    <div className="max-h-52 overflow-y-auto scrollbar-thin">
      {total != null && (
        <p className="pb-1.5 text-xs text-slate-500">
          근처 도서관 {total}곳 중 {libraries.length}곳 표시
        </p>
      )}

      {libraries.map((lib, i) => (
        <div key={i} className="py-2.5 border-b border-slate-50 last:border-0">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="text-sm font-medium text-slate-700 truncate">{lib.libraryName}</p>
              <p className="text-xs text-slate-500 mt-0.5">{lib.distance}km · {lib.address}</p>
            </div>
            <div className="shrink-0 flex flex-col items-end gap-1">
              <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.hasBook ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-600'}`}>
                {lib.hasBook ? '보유' : '미보유'}
              </span>
              {lib.hasBook && (
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.loanAvailable ? 'bg-blue-50 text-blue-600' : 'bg-orange-50 text-orange-500'}`}>
                  {lib.loanAvailable ? '대출가능' : '대출중'}
                </span>
              )}
            </div>
          </div>
          {/* Link buttons */}
          <div className="flex items-center gap-1.5 mt-2 flex-wrap">
            {isSafeUrl(lib.homepage) && (
              <a
                href={lib.homepage}
                target="_blank"
                rel="noopener noreferrer"
                aria-label={`${lib.libraryName} 홈페이지 열기`}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-slate-50
                  border border-slate-100 text-xs text-slate-600 hover:border-primary
                  hover:text-primary transition"
              >
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <circle cx="12" cy="12" r="10" />
                  <path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                </svg>
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
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs
                    text-brand-kakao-dark bg-brand-kakao hover:bg-brand-kakao-hover transition font-medium"
                >
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                    <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                    <circle cx="12" cy="10" r="3" />
                  </svg>
                  장소 정보
                </a>
                <a
                  href={`https://map.kakao.com/link/to/${encodeURIComponent(lib.libraryName)},${lib.latitude},${lib.longitude}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`${lib.libraryName} 길찾기`}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs
                    text-white bg-brand-kakao-dark hover:bg-black transition font-medium"
                >
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                    <polygon points="3 11 22 2 13 21 11 13 3 11" />
                  </svg>
                  길찾기
                </a>
              </>
            )}
          </div>
        </div>
      ))}

      {libraries.length === 0 && (
        <p className="text-sm text-slate-600 text-center py-2">
          근처 도서관의 응답을 받지 못했어요
        </p>
      )}

      {skippedCount > 0 && (
        <p className="text-xs text-slate-400 text-center py-1.5">
          도서관 {skippedCount}곳은 응답이 없어 건너뛰었어요
        </p>
      )}

      {moreError && (
        <p className="text-xs text-red-500 text-center py-1.5">{moreError}</p>
      )}

      {hasMore && (
        <button
          onClick={loadMore}
          disabled={loadingMore}
          aria-busy={loadingMore}
          className="w-full flex items-center justify-center py-2 text-xs text-slate-600
            hover:text-slate-800 transition cursor-pointer disabled:cursor-default"
        >
          {loadingMore ? (
            <>
              <span
                aria-hidden="true"
                className="w-4 h-4 border-2 border-slate-200 border-t-slate-500 rounded-full animate-spin"
              />
              <span className="sr-only">근처 도서관 불러오는 중</span>
            </>
          ) : (
            `+ 근처 도서관 ${nextBatch != null ? `최대 ${nextBatch}곳 ` : ''}더 보기`
          )}
        </button>
      )}
    </div>
  )
}
