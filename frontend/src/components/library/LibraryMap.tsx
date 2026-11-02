import { useEffect, useRef, useState } from 'react'
import { RefreshIcon, SpinnerIcon } from '../shared/icons'
import { loadKakaoMaps, type KakaoCustomOverlay, type KakaoMap, type KakaoMapsApi } from './kakaoMaps.ts'
import type { MapPoint, PointTone } from './mapPoints.ts'

const TONE_COLOR: Record<PointTone, string> = {
  ok: '#146C2E',      // status.ok
  off: '#4E5968',     // status.off
  unknown: '#8B95A1', // ink.400
}

/** 마커 DOM. 기관명은 텍스트로만 넣는다(외부 문자열을 HTML로 넣지 않음) */
function markerElement(point: MapPoint, selected: boolean, onSelect: (code: string) => void): HTMLElement {
  const button = document.createElement('button')
  button.type = 'button'
  button.textContent = point.label
  button.setAttribute('aria-label', `${point.label}. ${point.name} ${point.stateLabel}`)
  button.setAttribute('aria-pressed', String(selected))
  const size = selected ? 34 : 28
  Object.assign(button.style, {
    width: `${size}px`,
    height: `${size}px`,
    borderRadius: '9999px',
    background: TONE_COLOR[point.tone],
    color: '#fff',
    font: `700 ${selected ? 15 : 13}px/1 system-ui, sans-serif`,
    border: selected ? '3px solid #FF6B00' : '2px solid #fff',
    boxShadow: '0 1px 4px rgba(0,0,0,.35)',
    cursor: 'pointer',
    // 대출중·미소장은 색만으로 구분하지 않게 점선 테두리를 더한다
    outline: point.tone === 'ok' ? 'none' : '1px dashed rgba(255,255,255,.9)',
    outlineOffset: '-5px',
  } satisfies Partial<CSSStyleDeclaration>)
  button.addEventListener('click', event => {
    event.stopPropagation()
    onSelect(point.code)
  })
  return button
}

function originElement(label: string): HTMLElement {
  const el = document.createElement('div')
  el.textContent = label
  el.setAttribute('role', 'img')
  el.setAttribute('aria-label', `기준: ${label}`)
  Object.assign(el.style, {
    padding: '3px 8px',
    borderRadius: '9999px',
    background: '#191F28',
    color: '#fff',
    font: '600 12px/1.4 system-ui, sans-serif',
    whiteSpace: 'nowrap',
    boxShadow: '0 1px 4px rgba(0,0,0,.35)',
  } satisfies Partial<CSSStyleDeclaration>)
  return el
}

/**
 * 목록과 같은 기관을 카카오 지도에 그린다(D7-1 T7). 지도 이동만으로 다시 조회하지 않는다.
 * 사용자가 지도를 옮기기 전에는 결과가 늘 때마다 전체가 보이게 맞추고, 옮긴 뒤에는 '전체 보기'로만 맞춘다.
 */
export function LibraryMap({
  points,
  origin,
  selectedCode,
  onSelect,
}: {
  points: MapPoint[]
  origin: { lat: number; lng: number; label: string } | null
  selectedCode: string | null
  onSelect: (code: string) => void
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<KakaoMap | null>(null)
  const apiRef = useRef<KakaoMapsApi | null>(null)
  const overlaysRef = useRef(new Map<string, KakaoCustomOverlay>())
  const originOverlayRef = useRef<KakaoCustomOverlay | null>(null)
  const userMovedRef = useRef(false)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [attempt, setAttempt] = useState(0)

  // SDK 로드 + 지도 만들기(한 번)
  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    loadKakaoMaps()
      .then(api => {
        if (cancelled || !containerRef.current) return
        apiRef.current = api
        const first = points[0] ?? (origin ? { lat: origin.lat, lng: origin.lng } : { lat: 37.5665, lng: 126.978 })
        const map = new api.Map(containerRef.current, { center: new api.LatLng(first.lat, first.lng), level: 6 })
        mapRef.current = map
        api.event.addListener(map, 'dragstart', () => { userMovedRef.current = true })
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('error')
      })
    return () => {
      cancelled = true
      overlaysRef.current.forEach(o => o.setMap(null))
      overlaysRef.current.clear()
      originOverlayRef.current?.setMap(null)
      originOverlayRef.current = null
      mapRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt])

  function fitAll() {
    const api = apiRef.current
    const map = mapRef.current
    if (!api || !map) return
    const all = [...points.map(p => ({ lat: p.lat, lng: p.lng })), ...(origin ? [origin] : [])]
    if (all.length === 0) return
    if (all.length === 1) {
      map.setCenter(new api.LatLng(all[0].lat, all[0].lng))
      map.setLevel(5)
      return
    }
    const bounds = new api.LatLngBounds()
    all.forEach(p => bounds.extend(new api.LatLng(p.lat, p.lng)))
    map.setBounds(bounds, 40, 40, 40, 40)
  }

  // 마커를 libCode로 맞춘다: 새로 온 것만 더하고, 빠진 것은 지우고, 선택 표시는 다시 그린다
  const pointsKey = points.map(p => `${p.code}:${p.tone}:${p.label}`).join('|')
  useEffect(() => {
    const api = apiRef.current
    const map = mapRef.current
    if (status !== 'ready' || !api || !map) return
    const live = new Set(points.map(p => p.code))
    overlaysRef.current.forEach((overlay, code) => {
      if (!live.has(code)) {
        overlay.setMap(null)
        overlaysRef.current.delete(code)
      }
    })
    points.forEach(point => {
      const selected = point.code === selectedCode
      const content = markerElement(point, selected, code => onSelectRef.current(code))
      const existing = overlaysRef.current.get(point.code)
      if (existing) {
        existing.setContent(content)
        existing.setZIndex(selected ? 10 : 1)
      } else {
        const overlay = new api.CustomOverlay({
          position: new api.LatLng(point.lat, point.lng),
          content,
          yAnchor: 0.5,
          zIndex: selected ? 10 : 1,
          clickable: true,
        })
        overlay.setMap(map)
        overlaysRef.current.set(point.code, overlay)
      }
    })
    if (!userMovedRef.current) fitAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, pointsKey, selectedCode])

  // 기준점(내 위치·기준 도서관)
  const originKey = origin ? `${origin.lat},${origin.lng},${origin.label}` : ''
  useEffect(() => {
    const api = apiRef.current
    const map = mapRef.current
    originOverlayRef.current?.setMap(null)
    originOverlayRef.current = null
    if (status !== 'ready' || !api || !map || !origin) return
    const overlay = new api.CustomOverlay({
      position: new api.LatLng(origin.lat, origin.lng),
      content: originElement(origin.label),
      yAnchor: 1.6,
      zIndex: 5,
    })
    overlay.setMap(map)
    originOverlayRef.current = overlay
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, originKey])

  // 목록에서 고르면 그 마커로 옮긴다
  useEffect(() => {
    const api = apiRef.current
    const map = mapRef.current
    if (status !== 'ready' || !api || !map || !selectedCode) return
    const point = points.find(p => p.code === selectedCode)
    if (point) map.panTo(new api.LatLng(point.lat, point.lng))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCode, status])

  // 펼침·화면 크기 변경 뒤에는 지도 크기를 다시 계산해야 타일이 비지 않는다
  useEffect(() => {
    const el = containerRef.current
    if (!el || status !== 'ready' || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => mapRef.current?.relayout())
    observer.observe(el)
    return () => observer.disconnect()
  }, [status])

  return (
    <div className="relative">
      {/* 지도는 마우스·터치용이다. 키보드·화면 낭독기로는 목록에서 같은 일을 모두 할 수 있다 */}
      <div ref={containerRef} className="h-72 lg:h-80 w-full rounded-xl overflow-hidden bg-ink-50" aria-hidden={status !== 'ready'} />
      {status === 'loading' && (
        <div className="absolute inset-0 flex items-center justify-center text-ink-500">
          <SpinnerIcon size={22} strokeWidth={2.2} />
          <span className="sr-only">지도를 불러오는 중</span>
        </div>
      )}
      {status === 'error' && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 rounded-xl bg-ink-50 text-meta text-ink-700">
          <p>지도를 불러오지 못했어요. 목록은 그대로 볼 수 있어요</p>
          <button
            type="button"
            onClick={() => setAttempt(a => a + 1)}
            className="inline-flex items-center gap-1.5 min-h-10 px-3 font-semibold text-primary hover:underline cursor-pointer"
          >
            <RefreshIcon size={14} strokeWidth={2} />
            다시 시도
          </button>
        </div>
      )}
      {status === 'ready' && points.length > 1 && (
        <button
          type="button"
          onClick={() => {
            userMovedRef.current = false
            fitAll()
          }}
          className="absolute top-2 right-2 z-10 min-h-9 px-3 rounded-lg bg-white/95 border border-ink-200 text-label font-semibold text-ink-900 shadow-sm hover:border-ink-400 cursor-pointer"
        >
          전체 보기
        </button>
      )}
    </div>
  )
}
