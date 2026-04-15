import { useEffect, useRef, useState } from 'react'
import { getOffStores } from '../api'
import type { OffStoreInfo } from '../types'

interface Props {
  isbn13: string
  lat: number | null
  lon: number | null
  spaceUsedPrice: number | null
  spaceUsedUrl: string | null
  onRequestLocation: () => void
}

const INITIAL_SHOW = 3
const LOAD_MORE_COUNT = 5

function isSafeUrl(url: string | null | undefined): url is string {
  if (!url) return false
  try {
    const { protocol } = new URL(url)
    return protocol === 'http:' || protocol === 'https:'
  } catch {
    return false
  }
}

export function OffStoreList({
  isbn13,
  lat,
  lon,
  spaceUsedPrice,
  spaceUsedUrl,
  onRequestLocation,
}: Props) {
  const [expanded, setExpanded] = useState(false)
  const [stores, setStores] = useState<OffStoreInfo[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [visibleCount, setVisibleCount] = useState(INITIAL_SHOW)
  const requestAbort = useRef<AbortController | null>(null)

  useEffect(() => {
    setExpanded(false)
    setStores(null)
    setLoading(false)
    setError(null)
    setVisibleCount(INITIAL_SHOW)
    requestAbort.current?.abort()
  }, [isbn13])

  useEffect(() => {
    return () => {
      requestAbort.current?.abort()
    }
  }, [])

  if (spaceUsedPrice == null) return null

  function loadStores(currentLat: number, currentLon: number) {
    requestAbort.current?.abort()
    const controller = new AbortController()
    requestAbort.current = controller
    setLoading(true)
    setError(null)

    getOffStores(isbn13, currentLat, currentLon, controller.signal)
      .then(res => {
        if (requestAbort.current === controller) {
          setStores(res.stores)
          setLoading(false)
        }
      })
      .catch(err => {
        if (err?.name === 'AbortError') return
        if (requestAbort.current === controller) {
          setError('매장 정보를 가져올 수 없습니다')
          setLoading(false)
        }
      })
  }

  useEffect(() => {
    if (!expanded || stores !== null || lat == null || lon == null) return
    loadStores(lat, lon)
  }, [expanded, stores, isbn13, lat, lon])

  function handleToggle() {
    if (expanded) {
      setExpanded(false)
      setVisibleCount(INITIAL_SHOW)
      return
    }

    setExpanded(true)
    if (stores !== null || lat == null || lon == null) return
    loadStores(lat, lon)
  }

  const visibleStores = stores?.slice(0, visibleCount)
  const remainingCount = stores == null ? 0 : stores.length - visibleCount
  const hasMore = remainingCount > 0

  return (
    <div className="border-b border-slate-50 last:border-0">
      <div
        className="flex items-center justify-between py-2.5 cursor-pointer"
        onClick={handleToggle}
      >
        <p className="text-sm text-slate-600">알라딘 매장</p>
        <div className="flex items-center gap-2">
          <div className="flex flex-col items-end">
            <p className="text-[11px] leading-none text-slate-400 mb-1">최저가</p>
            <div className="flex items-center gap-2">
              <span className="font-semibold text-slate-800 text-sm">
                {spaceUsedPrice.toLocaleString()}원
              </span>
              {isSafeUrl(spaceUsedUrl) && (
                <a
                  href={spaceUsedUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={e => e.stopPropagation()}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-[#e8400c]/10
                    text-xs text-[#e8400c] font-medium hover:bg-[#e8400c]/20 transition"
                >
                  알라딘
                  <svg
                    width="9"
                    height="9"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                  >
                    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                  </svg>
                </a>
              )}
            </div>
          </div>
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            className={`text-slate-400 transition-transform ${expanded ? 'rotate-180' : ''}`}
          >
            <path d="M6 9l6 6 6-6" />
          </svg>
        </div>
      </div>

      {expanded && (
        <div className="pb-3">
          {lat == null || lon == null ? (
            <div className="text-center py-3 bg-slate-50 rounded-xl">
              <p className="text-sm text-slate-400 mb-2">
                위치를 허용하면 근처 매장 재고를
                <br />
                확인할 수 있어요
              </p>
              <button
                onClick={e => {
                  e.stopPropagation()
                  onRequestLocation()
                }}
                className="px-4 py-2 rounded-xl bg-primary text-white text-sm font-medium
                  hover:bg-primary/90 transition cursor-pointer"
              >
                위치 허용하기
              </button>
            </div>
          ) : loading ? (
            <div className="flex justify-center py-3">
              <div className="w-5 h-5 border-2 border-slate-200 border-t-slate-500 rounded-full animate-spin" />
            </div>
          ) : error ? (
            <p className="text-sm text-red-400 text-center py-2">{error}</p>
          ) : stores != null && stores.length === 0 ? (
            <p className="text-sm text-slate-400 text-center py-2">
              근처 매장에 재고가 없습니다
            </p>
          ) : visibleStores != null ? (
            <div className="space-y-1">
              <p className="px-1 pb-1 text-xs text-slate-400">
                내 위치 기준 가까운 순 · 매장별 가격은 알라딘에서 확인
              </p>
              {visibleStores.map(store => (
                <a
                  key={`${store.storeName}-${store.link}`}
                  href={store.link}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center justify-between gap-3 px-3 py-2 rounded-xl
                    hover:bg-slate-50 transition"
                >
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm text-slate-700">{store.storeName}</span>
                      {store.distance != null && (
                        <span className="text-xs text-slate-400">{store.distance}km</span>
                      )}
                    </div>
                    {store.address && (
                      <p className="text-xs text-slate-400 truncate mt-0.5">{store.address}</p>
                    )}
                  </div>
                  <svg
                    width="9"
                    height="9"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                    className="text-slate-400 shrink-0"
                  >
                    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                  </svg>
                </a>
              ))}
              {hasMore && (
                <button
                  onClick={e => {
                    e.stopPropagation()
                    setVisibleCount(current => current + LOAD_MORE_COUNT)
                  }}
                  className="w-full text-center py-2 text-xs text-slate-400
                    hover:text-slate-600 transition cursor-pointer"
                >
                  + {Math.min(LOAD_MORE_COUNT, remainingCount)}곳 더 보기
                </button>
              )}
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}
