import type {
  BookCandidateResponse,
  SearchResponse,
  ELibraryInfo,
  ELibrarySearchResponse,
  OffStoreResponse,
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

export function searchBooks(q: string, page = 1, size = 10): Promise<BookCandidateResponse> {
  return get(`/api/books/search?q=${encodeURIComponent(q)}&page=${page}&size=${size}`)
}

export function searchMain(
  q: string,
  lat?: number,
  lon?: number,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  const params = new URLSearchParams({ q })
  if (lat != null) params.set('lat', String(lat))
  if (lon != null) params.set('lon', String(lon))
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
