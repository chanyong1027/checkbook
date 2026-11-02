import { useEffect, useState } from 'react'
import { getOffStores } from '../api'
import { isSafeUrl } from '../utils/url'
import type { OffStoreInfo } from '../types'
import { RefreshIcon, SpinnerIcon } from './shared/icons'
import { formatDistance } from '../utils/distance'

interface Props {
  isbn13: string
  lat: number | null
  lon: number | null
}

/**
 * 가까운 알라딘 매장 5곳과 매장별 재고 확인 링크.
 * 재고는 조회하지 않는다(알라딘 robots.txt가 자동 수집을 막음) — 사용자가 매장 페이지에서 직접 확인한다.
 * 위치는 책 패널 한 곳에서만 묻는다(도서관 칸과 같은 위치) — 여기서는 안내만 한다.
 * 목록만 그리는 컴포넌트라 나중에 지도 옆에 그대로 붙일 수 있다(지도는 별도 작업).
 */
export function OffStoreList({ isbn13, lat, lon }: Props) {
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
          setError('매장 정보를 가져오지 못했어요. 잠시 후 다시 시도해주세요.')
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [isbn13, lat, lon, attempt])

  if (lat == null || lon == null) {
    return <p className="text-body text-ink-600 py-1">위치를 허용하면 가까운 알라딘 중고매장 5곳을 보여 드려요</p>
  }

  return (
    <div>
      <p className="text-meta text-ink-600 mb-2">내 위치에서 가까운 5곳 · 재고는 매장 페이지에서 확인해 주세요</p>
      {/* 라이브 영역은 상태 문구로만 좁힌다 — 목록 전체를 넣으면 도착할 때 다섯 곳이 통째로 읽힌다 */}
      {loading ? (
        <div role="status" className="flex items-center gap-2 py-3 text-meta text-ink-600">
          <SpinnerIcon size={16} strokeWidth={2.2} />
          가까운 매장을 찾고 있어요
        </div>
      ) : error ? (
        <div className="flex items-center justify-between gap-3 py-2">
          <p role="alert" className="text-meta text-status-danger">{error}</p>
          <button
            type="button"
            onClick={() => setAttempt(value => value + 1)}
            className="inline-flex items-center gap-1.5 min-h-10 px-2 text-meta font-semibold text-primary hover:text-primary-dark cursor-pointer shrink-0"
          >
            <RefreshIcon size={14} strokeWidth={2} />
            다시 시도
          </button>
        </div>
      ) : stores?.length === 0 ? (
        <p role="status" className="text-body text-ink-600 py-1">등록된 매장 정보가 없어요</p>
      ) : (
        <ol role="list">
          {stores?.map(store => (
            <li key={store.link} className="flex items-center justify-between gap-3 py-3 border-t border-ink-100 first:border-t-0">
              <div className="min-w-0">
                <div className="flex items-baseline gap-2">
                  <span className="text-body font-semibold text-ink-900">{store.storeName}</span>
                  {store.distance != null && <span className="text-meta text-ink-600 tabular-nums">{formatDistance(store.distance)}</span>}
                </div>
                {store.address && <p className="text-meta text-ink-600 truncate">{store.address}</p>}
              </div>
              {isSafeUrl(store.link) && (
                <a
                  href={store.link}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`${store.storeName} 재고 확인 (알라딘 매장 페이지, 새 창)`}
                  className="shrink-0 inline-flex items-center min-h-10 px-3 rounded-lg border border-ink-200 text-label font-semibold text-ink-900 hover:border-ink-400 transition"
                >
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
