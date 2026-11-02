import type {
  BookCandidateResponse,
  SearchResponse,
  ELibraryInfo,
  ELibrarySearchResponse,
  OffStoreResponse,
  FeaturedBooksResponse,
  FeaturedSectionType,
  PublicLibraryDirectoryResponse,
  PublicLibraryBatchResponse,
} from '../types'
import { toApiError } from './errors.ts'

// import.meta.env는 Vite가 채운다. Vite 없이 도는 단위 테스트(node --test)에서는 없으므로 같은 출처('')로 둔다
const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''

/**
 * 서버로 보내는 좌표는 소수 셋째 자리(약 100m)로 줄인다. 반경 10km 탐색·가까운 매장에는 충분하고,
 * 혹시 중간 경로(로그·CDN)에 남아도 집 주소 수준의 정밀 위치가 되지 않게 한다. 화면(지도의 내 위치)은 원래 값을 쓴다
 */
export function coarseCoordinate(value: number): number {
  return Math.round(value * 1000) / 1000
}

function apiUrl(path: string): string {
  return `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(apiUrl(path), signal ? { signal } : undefined)
  if (!res.ok) {
    throw await toApiError(res)
  }
  return res.json() as Promise<T>
}

export function searchBooks(
  q: string,
  page = 1,
  size = 10,
  signal?: AbortSignal,
): Promise<BookCandidateResponse> {
  return get(
    `/api/books/search?q=${encodeURIComponent(q)}&page=${page}&size=${size}`,
    signal,
  )
}

/** 상세 화면이 이미 가진 후보 정보. YES24가 실패하거나 못 찾을 때 서버가 표시·밀리 매칭에 쓴다 */
export interface SearchHint {
  title?: string
  author?: string
  publisher?: string
  cover?: string
}

export function searchMain(
  q: string,
  lat?: number,
  lon?: number,
  signal?: AbortSignal,
  hint?: SearchHint | null,
): Promise<SearchResponse> {
  const params = new URLSearchParams({ q })
  if (lat != null) params.set('lat', String(lat))
  if (lon != null) params.set('lon', String(lon))
  if (hint) {
    // 빈 값은 보내지 않는다(서버는 모두 비면 힌트 없음으로 본다)
    if (hint.title) params.set('title', hint.title)
    if (hint.author) params.set('author', hint.author)
    if (hint.publisher) params.set('publisher', hint.publisher)
    if (hint.cover) params.set('cover', hint.cover)
  }
  return get(`/api/search?${params}`, signal)
}

export function getELibraries(params?: {
  region?: string
  vendorType?: string
  keyword?: string
}): Promise<ELibraryInfo[]> {
  const p = new URLSearchParams()
  if (params?.region) p.set('region', params.region)
  if (params?.vendorType) p.set('vendorType', params.vendorType)
  if (params?.keyword) p.set('keyword', params.keyword)
  const qs = p.toString()
  return get(`/api/elibraries${qs ? `?${qs}` : ''}`)
}

export function searchELibraries(
  title: string,
  author: string | null | undefined,
  libraryIds: string,
  signal?: AbortSignal,
): Promise<ELibrarySearchResponse> {
  const p = new URLSearchParams({ title, libraryIds })
  if (author) p.set('author', author)
  return get(`/api/elibraries/search?${p}`, signal)
}

export function getOffStores(
  isbn13: string,
  lat: number,
  lon: number,
  signal?: AbortSignal,
): Promise<OffStoreResponse> {
  const params = new URLSearchParams({
    isbn13,
    lat: String(coarseCoordinate(lat)),
    lon: String(coarseCoordinate(lon)),
  })
  return get(`/api/off-stores?${params}`, signal)
}

/** 기관 이름·주소 검색(자체 DB만 읽음 — 정보나루를 부르지 않는다). 내 도서관·기준 도서관 고르기용 */
export function searchPublicLibraries(
  keyword: string,
  page = 0,
  size = 20,
  signal?: AbortSignal,
): Promise<PublicLibraryDirectoryResponse> {
  const params = new URLSearchParams({ keyword, page: String(page), size: String(size) })
  return get(`/api/public-libraries?${params}`, signal)
}

/** 주변 도서관 5곳 배치. 기준은 좌표 또는 기준 도서관 중 하나. 한 탐색 동안 같은 기준을 보내야 offset이 이어진다 */
export function getNearbyLibraryBatch(
  isbn13: string,
  origin: { lat: number; lon: number } | { referenceLibCode: string },
  offset: number,
  signal?: AbortSignal,
): Promise<PublicLibraryBatchResponse> {
  const params = new URLSearchParams({ isbn13, mode: 'NEARBY', offset: String(offset) })
  if ('referenceLibCode' in origin) {
    params.set('referenceLibCode', origin.referenceLibCode)
  } else {
    params.set('lat', String(coarseCoordinate(origin.lat)))
    params.set('lon', String(coarseCoordinate(origin.lon)))
  }
  return get(`/api/public-libraries/availability/batch?${params}`, signal)
}

/** 내 도서관(최대 5곳) 또는 확인 못 한 곳 다시 확인. 거리·nextOffset 없음 */
export function getSavedLibraryBatch(
  isbn13: string,
  libCodes: string[],
  signal?: AbortSignal,
): Promise<PublicLibraryBatchResponse> {
  const params = new URLSearchParams({ isbn13, mode: 'SAVED', libraryCodes: libCodes.join(',') })
  return get(`/api/public-libraries/availability/batch?${params}`, signal)
}

export function getFeatured(
  type: FeaturedSectionType,
  signal?: AbortSignal,
): Promise<FeaturedBooksResponse> {
  return get(`/api/featured?type=${encodeURIComponent(type)}`, signal)
}
