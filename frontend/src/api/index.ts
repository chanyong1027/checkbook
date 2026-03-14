import type {
  BookCandidateResponse,
  SearchResponse,
  ELibraryInfo,
  ELibrarySearchResponse,
} from '../types'

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status} ${text}`)
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
): Promise<SearchResponse> {
  const params = new URLSearchParams({ q })
  if (lat != null) params.set('lat', String(lat))
  if (lon != null) params.set('lon', String(lon))
  return get(`/api/search?${params}`)
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
  query: string,
  libraryIds: string,
  fallbackKeyword?: string,
): Promise<ELibrarySearchResponse> {
  const p = new URLSearchParams({ query, libraryIds })
  if (fallbackKeyword) p.set('fallbackKeyword', fallbackKeyword)
  return get(`/api/elibraries/search?${p}`)
}
