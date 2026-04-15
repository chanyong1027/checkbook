// /api/books/search
export interface BookCandidate {
  title: string
  author: string
  publisher: string
  isbn13: string
  coverUrl: string
  publishedAt: string
}

export interface Pagination {
  page: number
  size: number
  totalCount: number
  isEnd: boolean
}

export interface BookCandidateResponse {
  items: BookCandidate[]
  pagination: Pagination
}

// /api/search
export interface BookInfo {
  title: string | null
  author: string | null
  isbn13: string | null
  publisher: string | null
  coverUrl: string | null
}

export interface PublicLibraryInfo {
  libraryName: string
  hasBook: boolean
  loanAvailable: boolean
  address: string
  latitude: number
  longitude: number
  distance: number
  homepage: string
}

export interface UsedBookInfo {
  userUsedPrice: number | null
  aladinUsedPrice: number | null
  spaceUsedPrice: number | null
  userUsedUrl: string | null
  aladinUsedUrl: string | null
  spaceUsedUrl: string | null
}

export interface OffStoreInfo {
  storeName: string
  address: string | null
  distance: number | null
  link: string
  latitude: number | null
  longitude: number | null
}

export interface OffStoreResponse {
  stores: OffStoreInfo[]
}

export interface NewBookInfo {
  price: number
  productUrl: string
}

export type SearchSectionStatus = 'SUCCESS' | 'FAILED' | 'SKIPPED'

export interface SectionStatusDetail {
  section: string
  status: SearchSectionStatus
}

export interface FailureDetail {
  section: string
  reason: string
}

export interface SearchMetadata {
  searchedAt: string
  sectionStatuses: SectionStatusDetail[]
  failures: FailureDetail[]
}

export interface SearchResponse {
  book: BookInfo
  publicLibraries: PublicLibraryInfo[]
  usedBook: UsedBookInfo | null
  newBook: NewBookInfo | null
  metadata: SearchMetadata
}

// /api/elibraries
export interface ELibraryInfo {
  libraryId: number
  name: string
  vendorType: string
  region: string
}

// /api/elibraries/search
export interface ELibraryBook {
  title: string
  author: string
  publisher: string
  coverUrl: string
  available: boolean
  detailUrl: string
}

export type ELibrarySearchStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT'

export interface ELibraryResult {
  libraryId: number
  libraryName: string
  vendorType: string
  books: ELibraryBook[]
  status: ELibrarySearchStatus
  elapsedMs: number
}

export interface ELibraryFailureDetail {
  libraryId: number
  libraryName: string | null
  reason: string
}

export interface ELibrarySearchMetadata {
  totalElapsedMs: number
  searchedAt: string
  failures: ELibraryFailureDetail[]
}

export interface ELibrarySearchResponse {
  results: ELibraryResult[]
  metadata: ELibrarySearchMetadata
}
