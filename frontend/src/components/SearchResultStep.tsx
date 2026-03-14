import { useState, useEffect } from 'react'
import { searchMain } from '../api'
import type { BookCandidate, SearchResponse, SearchSectionStatus } from '../types'
import { Spinner } from './Spinner'

interface Props {
  book: BookCandidate
  onNext: (isbn13: string, title: string) => void
  onBack: () => void
}

function StatusBadge({ status }: { status: SearchSectionStatus }) {
  const map: Record<SearchSectionStatus, { label: string; cls: string }> = {
    SUCCESS: { label: '성공', cls: 'bg-green-50 text-green-700 border-green-100' },
    FAILED: { label: '실패', cls: 'bg-red-50 text-red-600 border-red-100' },
    SKIPPED: { label: '건너뜀', cls: 'bg-slate-100 text-slate-500 border-slate-200' },
  }
  const { label, cls } = map[status]
  return <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${cls}`}>{label}</span>
}

function PriceRow({ label, price }: { label: string; price: number | null | undefined }) {
  if (price == null) return null
  return (
    <div className="flex justify-between items-center py-2 border-b border-slate-50 last:border-0">
      <span className="text-sm text-slate-500">{label}</span>
      <span className="font-semibold text-slate-900">{price.toLocaleString()}원</span>
    </div>
  )
}

const SECTION_LABEL: Record<string, string> = {
  PUBLIC_LIBRARY: '공공도서관',
  USED_BOOK: '중고도서',
  NEW_BOOK: '신간',
}

export function SearchResultStep({ book, onNext, onBack }: Props) {
  const [useLocation, setUseLocation] = useState(false)
  const [lat, setLat] = useState<number | undefined>()
  const [lon, setLon] = useState<number | undefined>()
  const [locLoading, setLocLoading] = useState(false)
  const [result, setResult] = useState<SearchResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  async function fetchResult(withLat?: number, withLon?: number) {
    setLoading(true)
    setError(null)
    try {
      const res = await searchMain(book.isbn13, withLat, withLon)
      setResult(res)
    } catch (err) {
      setError(err instanceof Error ? err.message : '검색 실패')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchResult()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function handleGetLocation() {
    setLocLoading(true)
    navigator.geolocation.getCurrentPosition(
      pos => {
        const { latitude, longitude } = pos.coords
        setLat(latitude)
        setLon(longitude)
        setUseLocation(true)
        setLocLoading(false)
        fetchResult(latitude, longitude)
      },
      () => {
        setLocLoading(false)
        alert('위치 정보를 가져올 수 없습니다.')
      },
    )
  }

  const sectionStatuses = result?.metadata.sectionStatuses ?? []
  const statusMap = Object.fromEntries(sectionStatuses.map(s => [s.section, s.status]))

  return (
    <div>
      <div className="flex items-center gap-3 mb-6">
        <button onClick={onBack} className="p-2 rounded-lg hover:bg-slate-100 transition cursor-pointer" aria-label="뒤로">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#64748b" strokeWidth="2.5" strokeLinecap="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        {book.coverUrl && <img src={book.coverUrl} alt={book.title} className="w-12 h-16 object-cover rounded-lg shadow-sm" />}
        <div>
          <h2 className="text-lg font-bold text-slate-900 leading-tight">{book.title}</h2>
          <p className="text-sm text-slate-500">{book.author} · <span className="font-mono text-xs">{book.isbn13}</span></p>
        </div>
      </div>

      {!useLocation && !loading && (
        <button
          onClick={handleGetLocation}
          disabled={locLoading}
          className="w-full mb-4 py-2.5 px-4 rounded-xl border border-dashed border-slate-300 text-sm text-slate-500
            hover:border-primary hover:text-primary transition flex items-center justify-center gap-2 cursor-pointer"
        >
          {locLoading ? <Spinner size={16} /> : (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <circle cx="12" cy="12" r="3" /><path d="M12 1v4M12 19v4M4.22 4.22l2.83 2.83M16.95 16.95l2.83 2.83M1 12h4M19 12h4M4.22 19.78l2.83-2.83M16.95 7.05l2.83-2.83" />
            </svg>
          )}
          내 위치로 공공도서관 검색
        </button>
      )}

      {useLocation && (
        <div className="mb-4 flex items-center gap-2 text-xs text-green-600">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M20 6L9 17l-5-5" /></svg>
          위치 기반 검색 활성화 ({lat?.toFixed(4)}, {lon?.toFixed(4)})
        </div>
      )}

      {loading && (
        <div className="flex flex-col items-center justify-center py-16 gap-3 text-slate-400">
          <Spinner size={32} />
          <span className="text-sm">통합 검색 중…</span>
        </div>
      )}

      {error && (
        <div className="p-4 bg-red-50 border border-red-100 rounded-xl text-red-600 text-sm">{error}</div>
      )}

      {result && !loading && (
        <div className="space-y-4">
          {/* Section status summary */}
          {sectionStatuses.length > 0 && (
            <div className="flex gap-3 flex-wrap">
              {sectionStatuses.map(s => (
                <div key={s.section} className="flex items-center gap-1.5 text-xs text-slate-500">
                  <span>{SECTION_LABEL[s.section] ?? s.section}</span>
                  <StatusBadge status={s.status} />
                </div>
              ))}
            </div>
          )}

          {/* Used book */}
          {result.usedBook && (
            <div className="bg-white rounded-xl border border-slate-100 p-4">
              <h3 className="font-semibold text-slate-700 mb-2 flex items-center gap-2">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2563EB" strokeWidth="2" strokeLinecap="round"><path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13" /></svg>
                중고도서 가격
              </h3>
              <PriceRow label="개인판매" price={result.usedBook.userUsedPrice} />
              <PriceRow label="알라딘" price={result.usedBook.aladinUsedPrice} />
              <PriceRow label="알라딘 매장" price={result.usedBook.spaceUsedPrice} />
              {result.usedBook.detailUrl && (
                <a href={result.usedBook.detailUrl} target="_blank" rel="noopener noreferrer"
                  className="mt-3 inline-flex items-center gap-1 text-xs text-primary hover:underline">
                  알라딘에서 보기
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" /></svg>
                </a>
              )}
            </div>
          )}

          {/* New books */}
          {result.newBooks.length > 0 && (
            <div className="bg-white rounded-xl border border-slate-100 p-4">
              <h3 className="font-semibold text-slate-700 mb-2 flex items-center gap-2">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#F97316" strokeWidth="2" strokeLinecap="round"><path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 0 1-8 0" /></svg>
                신간 최저가
              </h3>
              <div className="space-y-1">
                {result.newBooks.map((b, i) => (
                  <div key={i} className="flex justify-between items-center py-1.5 border-b border-slate-50 last:border-0">
                    <a href={b.productUrl} target="_blank" rel="noopener noreferrer"
                      className="text-sm text-slate-600 hover:text-primary hover:underline">{b.mallName}</a>
                    <span className="font-semibold text-slate-900">{b.price.toLocaleString()}원</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Public libraries */}
          {result.publicLibraries.length > 0 && (
            <div className="bg-white rounded-xl border border-slate-100 p-4">
              <h3 className="font-semibold text-slate-700 mb-3 flex items-center gap-2">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#2563EB" strokeWidth="2" strokeLinecap="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><polyline points="9 22 9 12 15 12 15 22" /></svg>
                근처 공공도서관 ({result.publicLibraries.length}개)
              </h3>
              <div className="space-y-2 max-h-56 overflow-y-auto scrollbar-thin">
                {result.publicLibraries.map((lib, i) => (
                  <div key={i} className="flex items-center justify-between py-2 border-b border-slate-50 last:border-0">
                    <div className="min-w-0">
                      <a href={lib.homepage} target="_blank" rel="noopener noreferrer"
                        className="text-sm font-medium text-slate-700 hover:text-primary hover:underline truncate block">{lib.libraryName}</a>
                      <p className="text-xs text-slate-400">{lib.distance}km · {lib.address}</p>
                    </div>
                    <div className="ml-3 shrink-0 flex flex-col items-end gap-1">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.hasBook ? 'bg-green-50 text-green-700' : 'bg-slate-100 text-slate-400'}`}>
                        {lib.hasBook ? '보유' : '미보유'}
                      </span>
                      {lib.hasBook && (
                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.loanAvailable ? 'bg-blue-50 text-blue-600' : 'bg-orange-50 text-orange-500'}`}>
                          {lib.loanAvailable ? '대출가능' : '대출중'}
                        </span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {statusMap['PUBLIC_LIBRARY'] === 'SKIPPED' && (
            <p className="text-xs text-slate-400 text-center">위치를 허용하면 공공도서관 보유 현황을 확인할 수 있습니다</p>
          )}

          {/* Next step button */}
          <button
            onClick={() => onNext(book.isbn13, book.title)}
            className="w-full py-3 bg-accent text-white rounded-xl font-semibold hover:bg-orange-600 active:scale-95
              transition flex items-center justify-center gap-2 cursor-pointer"
          >
            전자도서관 소장 확인
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M9 18l6-6-6-6" />
            </svg>
          </button>
        </div>
      )}
    </div>
  )
}
