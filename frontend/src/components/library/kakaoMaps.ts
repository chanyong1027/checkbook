/**
 * 카카오 지도 Web SDK 로더(D7-1 결정 카드 T7). 공식 SDK를 직접 쓴다 — React 래퍼 패키지 없음.
 * autoload=false로 받고 kakao.maps.load로 준비를 기다린다. 페이지당 한 번만 넣고 Promise를 공유한다.
 * 실패하면 Promise를 비워 다시 시도할 수 있게 한다. 지도가 실패해도 목록은 그대로 쓴다.
 */

// 쓰는 부분만 최소로 선언한다(@types 패키지를 들이지 않음)
export interface KakaoLatLng { getLat(): number; getLng(): number }
export interface KakaoBounds { extend(latlng: KakaoLatLng): void }
export interface KakaoMap {
  setBounds(bounds: KakaoBounds, paddingTop?: number, paddingRight?: number, paddingBottom?: number, paddingLeft?: number): void
  setCenter(latlng: KakaoLatLng): void
  setLevel(level: number): void
  panTo(latlng: KakaoLatLng): void
  relayout(): void
}
export interface KakaoCustomOverlay { setMap(map: KakaoMap | null): void; setContent(content: HTMLElement): void; setZIndex(z: number): void }
export interface KakaoMapsApi {
  load(callback: () => void): void
  LatLng: new (lat: number, lng: number) => KakaoLatLng
  LatLngBounds: new () => KakaoBounds
  Map: new (container: HTMLElement, options: { center: KakaoLatLng; level: number }) => KakaoMap
  CustomOverlay: new (options: { position: KakaoLatLng; content: HTMLElement; yAnchor?: number; xAnchor?: number; zIndex?: number; clickable?: boolean }) => KakaoCustomOverlay
  event: { addListener(target: unknown, type: string, handler: () => void): void; removeListener(target: unknown, type: string, handler: () => void): void }
}

declare global {
  interface Window { kakao?: { maps: KakaoMapsApi } }
}

const SCRIPT_ID = 'kakao-maps-sdk'
const LOAD_TIMEOUT_MS = 10_000

export function kakaoMapKey(): string | null {
  const key = import.meta.env?.VITE_KAKAO_MAP_JAVASCRIPT_KEY?.trim()
  return key ? key : null
}

let pending: Promise<KakaoMapsApi> | null = null

export function loadKakaoMaps(): Promise<KakaoMapsApi> {
  if (pending) return pending
  const key = kakaoMapKey()
  if (!key) return Promise.reject(new Error('카카오 지도 키 없음'))

  pending = new Promise<KakaoMapsApi>((resolve, reject) => {
    const ready = () => {
      const maps = window.kakao?.maps
      if (!maps) {
        reject(new Error('카카오 지도 SDK를 찾지 못함'))
        return
      }
      maps.load(() => resolve(maps))
    }
    if (window.kakao?.maps) {
      ready()
      return
    }
    // 전에 실패한 스크립트가 남아 있으면 지우고 새로 넣는다(중복 삽입 방지)
    document.getElementById(SCRIPT_ID)?.remove()
    const script = document.createElement('script')
    script.id = SCRIPT_ID
    script.async = true
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`
    const timer = setTimeout(() => reject(new Error('카카오 지도 SDK 시간 초과')), LOAD_TIMEOUT_MS)
    script.onload = () => {
      clearTimeout(timer)
      ready()
    }
    script.onerror = () => {
      clearTimeout(timer)
      reject(new Error('카카오 지도 SDK를 불러오지 못함'))
    }
    document.head.appendChild(script)
  }).catch(err => {
    pending = null // 다시 시도할 수 있게
    throw err
  })
  return pending
}
