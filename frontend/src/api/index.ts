import type {
  BookCandidateResponse,
  SearchResponse,
  ELibraryInfo,
  ELibrarySearchResponse,
  OffStoreResponse,
  FeaturedBooksResponse,
  FeaturedSectionType,
  PublicLibraryAvailabilityResponse,
} from '../types'
import { toApiError } from './errors.ts'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? ''

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
    lat: String(lat),
    lon: String(lon),
  })
  return get(`/api/off-stores?${params}`, signal)
}

export function getPublicLibraryAvailability(
  isbn13: string,
  lat: number,
  lon: number,
  offset: number,
  signal?: AbortSignal,
): Promise<PublicLibraryAvailabilityResponse> {
  const params = new URLSearchParams({
    isbn13,
    lat: String(lat),
    lon: String(lon),
    offset: String(offset),
  })
  return get(`/api/public-libraries/availability?${params}`, signal)
}

export function getFeatured(
  type: FeaturedSectionType,
  signal?: AbortSignal,
): Promise<FeaturedBooksResponse> {
  return get(`/api/featured?type=${encodeURIComponent(type)}`, signal)
}
