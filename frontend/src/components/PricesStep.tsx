import { useState, useEffect } from 'react'
import { searchMain } from '../api'
import type { BookCandidate, SearchResponse } from '../types'

interface Props {
  book: BookCandidate
  onBack: () => void
  onReset: () => void
}

function Skeleton({ className }: { className?: string }) {
  return <div className={`bg-slate-100 rounded-lg ${className ?? ''}`} />
}

function LoadingSkeleton() {
  return (
    <div className="space-y-3 animate-pulse">
      <div className="bg-white rounded-2xl border border-orange-50 p-4">
        <Skeleton className="h-4 w-28 mb-3" />
        <div className="space-y-2.5">
          {[1, 2, 3].map(i => (
            <div key={i} className="flex items-center justify-between">
              <Skeleton className="h-3.5 w-24" />
              <Skeleton className="h-5 w-10 rounded-full" />
            </div>
          ))}
        </div>
      </div>
      <div className="bg-white rounded-2xl border border-orange-50 p-4">
        <Skeleton className="h-4 w-20 mb-3" />
        <div className="space-y-2.5">
          {[1, 2].map(i => (
            <div key={i} className="flex justify-between">
              <Skeleton className="h-3.5 w-16" />
              <Skeleton className="h-3.5 w-14" />
            </div>
          ))}
        </div>
      </div>
      <div className="bg-white rounded-2xl border border-orange-50 p-4">
        <Skeleton className="h-4 w-16 mb-3" />
        <div className="space-y-2.5">
          {[1, 2, 3].map(i => (
            <div key={i} className="flex justify-between">
              <Skeleton className="h-3.5 w-20" />
              <Skeleton className="h-3.5 w-12" />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function SectionCard({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 shadow-sm shadow-orange-50">
      <h3 className="text-[13px] font-semibold text-slate-500 flex items-center gap-1.5 mb-3">
        {icon}
        {title}
      </h3>
      {children}
    </div>
  )
}

export function PricesStep({ book, onBack, onReset }: Props) {
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

  function handleGetLocation() {
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

  return (
    <div>
      {/* Nav header */}
      <div className="flex items-center gap-2 mb-4">
        <button
          onClick={onBack}
          className="p-2 rounded-xl hover:bg-orange-50 transition cursor-pointer"
          aria-label="뒤로"
        >
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2.5" strokeLinecap="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <span className="text-[13px] font-medium text-slate-400">공공도서관 · 구매 정보</span>
      </div>

      {/* Book mini header */}
      <div className="flex items-center gap-3 bg-white rounded-2xl border border-orange-50 p-3 mb-4">
        {book.coverUrl && (
          <img src={book.coverUrl} alt={book.title} className="w-9 h-12 object-cover rounded-lg shadow-sm shrink-0" />
        )}
        <div className="min-w-0">
          <p className="font-bold text-slate-800 text-sm truncate">{book.title}</p>
          <p className="text-xs text-slate-400 truncate">{book.author}</p>
        </div>
      </div>

      {/* Location banner */}
      {!useLocation && !loading && result && (
        <button
          onClick={handleGetLocation}
          disabled={locLoading}
          className="w-full mb-4 py-2.5 px-4 rounded-2xl border border-dashed border-orange-200 text-sm
            text-slate-400 hover:border-primary hover:text-primary transition flex items-center
            justify-center gap-2 cursor-pointer bg-white"
        >
          {locLoading ? (
            <svg className="animate-spin" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
            </svg>
          ) : (
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M12 1v4M12 19v4M4.22 4.22l2.83 2.83M16.95 16.95l2.83 2.83M1 12h4M19 12h4M4.22 19.78l2.83-2.83M16.95 7.05l2.83-2.83" />
            </svg>
          )}
          내 위치로 공공도서관 검색
        </button>
      )}

      {useLocation && (
        <div className="mb-4 flex items-center gap-2 text-xs text-emerald-600 bg-emerald-50 rounded-xl px-3 py-2">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <path d="M20 6L9 17l-5-5" />
          </svg>
          위치 기반 검색 중 ({lat?.toFixed(3)}, {lon?.toFixed(3)})
        </div>
      )}

      {loading && <LoadingSkeleton />}

      {error && !loading && (
        <div className="p-4 bg-red-50 border border-red-100 rounded-2xl text-red-600 text-sm">
          {error}
        </div>
      )}

      {result && !loading && (
        <div className="space-y-3">
          {/* Public libraries */}
          {result.publicLibraries.length > 0 && (
            <SectionCard
              icon={
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                  <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                  <polyline points="9 22 9 12 15 12 15 22" />
                </svg>
              }
              title={`공공도서관 ${result.publicLibraries.length}개`}
            >
              <div className="max-h-52 overflow-y-auto scrollbar-thin">
                {result.publicLibraries.map((lib, i) => (
                  <div key={i} className="py-2.5 border-b border-slate-50 last:border-0">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-slate-700 truncate">{lib.libraryName}</p>
                        <p className="text-xs text-slate-300 mt-0.5">{lib.distance}km · {lib.address}</p>
                      </div>
                      <div className="shrink-0 flex flex-col items-end gap-1">
                        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.hasBook ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-400'}`}>
                          {lib.hasBook ? '보유' : '미보유'}
                        </span>
                        {lib.hasBook && (
                          <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${lib.loanAvailable ? 'bg-blue-50 text-blue-600' : 'bg-orange-50 text-orange-500'}`}>
                            {lib.loanAvailable ? '대출가능' : '대출중'}
                          </span>
                        )}
                      </div>
                    </div>
                    {/* Link buttons */}
                    <div className="flex items-center gap-1.5 mt-2 flex-wrap">
                      {lib.homepage && (
                        <a
                          href={lib.homepage}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-slate-50
                            border border-slate-100 text-xs text-slate-500 hover:border-primary
                            hover:text-primary transition"
                        >
                          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                            <circle cx="12" cy="12" r="10" />
                            <path d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
                          </svg>
                          홈페이지
                        </a>
                      )}
                      {lib.latitude != null && lib.longitude != null && (
                        <>
                          <a
                            href={`https://map.kakao.com/link/search/${encodeURIComponent(lib.libraryName)}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs
                              text-[#3A1D1D] bg-[#FEE500] hover:bg-[#FDD835] transition font-medium"
                          >
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                              <circle cx="12" cy="10" r="3" />
                            </svg>
                            장소 정보
                          </a>
                          <a
                            href={`https://map.kakao.com/link/to/${encodeURIComponent(lib.libraryName)},${lib.latitude},${lib.longitude}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs
                              text-white bg-[#3A1D1D] hover:bg-black transition font-medium"
                          >
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                              <polygon points="3 11 22 2 13 21 11 13 3 11" />
                            </svg>
                            길찾기
                          </a>
                        </>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {result.publicLibraries.length === 0 && !useLocation && (
            <p className="text-xs text-slate-300 text-center py-1">위치를 허용하면 근처 공공도서관을 볼 수 있어요</p>
          )}

          {/* Used book */}
          {result.usedBook && (
            <SectionCard
              icon={
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                  <path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13" />
                </svg>
              }
              title="중고도서"
            >
              <div className="space-y-0">
                {([
                  { label: '개인 판매', price: result.usedBook.userUsedPrice, url: result.usedBook.userUsedUrl },
                  { label: '알라딘 온라인', price: result.usedBook.aladinUsedPrice, url: result.usedBook.aladinUsedUrl },
                  { label: '알라딘 매장', price: result.usedBook.spaceUsedPrice, url: result.usedBook.spaceUsedUrl },
                ] as const).map(({ label, price, url }) => (
                  <div key={label} className="flex items-center justify-between py-2.5 border-b border-slate-50 last:border-0">
                    <p className="text-sm text-slate-600">{label}</p>
                    <div className="flex items-center gap-2">
                      {price != null ? (
                        <>
                          <span className="font-semibold text-slate-800 text-sm">{price.toLocaleString()}원</span>
                          {url && (
                            <a
                              href={url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-[#e8400c]/10
                                text-xs text-[#e8400c] font-medium hover:bg-[#e8400c]/20 transition"
                            >
                              알라딘
                              <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                              </svg>
                            </a>
                          )}
                        </>
                      ) : (
                        <span className="text-sm text-slate-400">재고 없음</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}

          {/* New book */}
          {result.newBook && (
            <SectionCard
              icon={
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2" strokeLinecap="round">
                  <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <path d="M16 10a4 4 0 0 1-8 0" />
                </svg>
              }
              title="새책"
            >
              <div className="flex items-center justify-between">
                <span className="text-sm text-slate-600">알라딘</span>
                <div className="flex items-center gap-2">
                  <span className="font-semibold text-slate-800 text-sm">{result.newBook.price.toLocaleString()}원</span>
                  <a
                    href={result.newBook.productUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-[#e8400c]/10
                      text-xs text-[#e8400c] font-medium hover:bg-[#e8400c]/20 transition"
                  >
                    알라딘
                    <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                      <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3" />
                    </svg>
                  </a>
                </div>
              </div>
            </SectionCard>
          )}

          <button
            onClick={onReset}
            className="w-full py-3 border border-orange-100 rounded-2xl text-sm text-slate-400
              hover:border-primary hover:text-primary transition cursor-pointer bg-white"
          >
            처음부터 다시 검색
          </button>
        </div>
      )}
    </div>
  )
}
