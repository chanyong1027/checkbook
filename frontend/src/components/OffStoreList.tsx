import { useEffect, useState } from 'react'
import { getOffStores } from '../api'
import { isSafeUrl } from '../utils/url'
import type { OffStoreInfo } from '../types'

interface Props {
  isbn13: string
  lat: number | null
  lon: number | null
  onRequestLocation: () => void
  locationLoading: boolean
  locationError: string | null
}

/**
 * 가까운 알라딘 매장 5곳과 매장별 재고 확인 링크.
 * 재고는 조회하지 않는다(알라딘 robots.txt가 자동 수집을 막음) — 사용자가 매장 페이지에서 직접 확인한다.
 * 목록만 그리는 컴포넌트라 나중에 지도 옆에 그대로 붙일 수 있다(지도는 별도 작업).
 */
export function OffStoreList({ isbn13, lat, lon, onRequestLocation, locationLoading, locationError }: Props) {
  const [stores, setStores] = useState<OffStoreInfo[] | null>(null)
  // 위치가 있으면 첫 렌더부터 로딩으로 — 요청 전 빈 목록이 깜빡이지 않게
  const [loading, setLoading] = useState(lat != null && lon != null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    setStores(null)
    setError(null)
    if (lat == null || lon == null) {
      setLoading(false)
      return
    }
    // ISBN·위치가 바뀌면 이전 요청을 취소해, 늦게 온 이전 응답이 새 목록을 덮어쓰지 못하게 한다
    const controller = new AbortController()
    setLoading(true)
    getOffStores(isbn13, lat, lon, controller.signal)
      .then(response => {
        if (!controller.signal.aborted) setStores(response.stores)
      })
      .catch(failure => {
        if (!controller.signal.aborted && failure?.name !== 'AbortError') {
          setError('매장 정보를 가져올 수 없습니다. 잠시 후 다시 시도해주세요.')
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [isbn13, lat, lon, attempt])

  if (lat == null || lon == null) {
    return (
      <div className="text-center py-3 bg-slate-50 rounded-xl">
        <p className="text-sm text-slate-600 mb-2">위치를 허용하면 가까운 알라딘 매장 5곳을 보여드려요</p>
        <button type="button" onClick={onRequestLocation} disabled={locationLoading}
          className="px-4 py-2 rounded-xl bg-primary text-white text-sm font-medium disabled:opacity-50">
          {locationLoading ? '위치 확인 중' : '위치 허용하기'}
        </button>
        {/* 위치 오류를 경고로 알리는 곳은 여기 하나(도서관 칸은 같은 문구를 글로만 보여 준다) */}
        {locationError && <p role="alert" className="mt-2 text-xs text-red-500">{locationError}</p>}
      </div>
    )
  }

  return (
    <div>
      <p className="text-xs text-slate-500 mb-3">내 위치에서 가까운 5곳 · 재고는 매장 페이지에서 확인해주세요</p>
      {/* 라이브 영역은 상태 문구로만 좁힌다 — 목록 전체를 넣으면 도착할 때 다섯 곳이 통째로 읽힌다 */}
      {loading ? (
        <div role="status" className="flex items-center gap-2 py-3 text-sm text-slate-600">
          <span className="w-4 h-4 border-2 border-slate-200 border-t-slate-500 rounded-full animate-spin" />
          가까운 매장을 찾고 있어요
        </div>
      ) : error ? (
        <>
          <p role="alert" className="text-sm text-red-500 py-2">{error}</p>
          <button type="button" onClick={() => setAttempt(value => value + 1)}
            className="mt-1 px-3 py-2 rounded-lg bg-slate-100 text-xs text-slate-600">다시 불러오기</button>
        </>
      ) : stores?.length === 0 ? (
        <p role="status" className="text-sm text-slate-600 py-2">등록된 매장 정보가 없습니다</p>
      ) : (
        <ol className="space-y-2">
          {stores?.map(store => (
            <li key={store.link} className="flex items-center justify-between gap-3 px-3 py-3 rounded-xl bg-slate-50">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-slate-700">{store.storeName}</span>
                  {store.distance != null && <span className="text-xs text-slate-500">{store.distance}km</span>}
                </div>
                {store.address && <p className="text-xs text-slate-500 mt-1 truncate">{store.address}</p>}
              </div>
              {isSafeUrl(store.link) && (
                <a href={store.link} target="_blank" rel="noopener noreferrer"
                  aria-label={`${store.storeName} 재고 확인 (알라딘 매장 페이지, 새 창)`}
                  className="shrink-0 px-2.5 py-1.5 rounded-lg bg-brand-aladin/10 text-xs text-brand-aladin font-medium hover:bg-brand-aladin/20 transition">
                  재고 확인
                </a>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
