import { useEffect, useRef, useState } from 'react'
import { searchPublicLibraries } from '../../api'
import { toUserMessage } from '../../api/errors.ts'
import type { PublicLibraryDirectoryItem } from '../../types'
import { MAX_PUBLIC_LIBRARIES, type SavedPublicLibrary } from '../../preferences/preferences.ts'
import { SearchIcon, SpinnerIcon } from '../shared/icons'
import { ChipRemoveButton } from './ChipRemoveButton'

const SEARCH_DEBOUNCE_MS = 300
const PAGE_SIZE = 20

/**
 * 공공도서관 고르기(설정 화면 안). 자체 DB 기관 검색으로 찾아
 * ① 내 도서관(최대 5곳, 멀어도 조회) ② 기준 도서관(위치 대신 주변 검색의 중심, 1곳)을 고른다. 둘은 별개다.
 */
export function PublicLibraryPicker({
  libraries,
  reference,
  onLibrariesChange,
  onReferenceChange,
}: {
  libraries: SavedPublicLibrary[]
  reference: SavedPublicLibrary | null
  onLibrariesChange: (libraries: SavedPublicLibrary[]) => void
  onReferenceChange: (reference: SavedPublicLibrary | null) => void
}) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<PublicLibraryDirectoryItem[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** 지금 결과가 어느 검색어의 것인지. 입력 직후(디바운스 중)에 "없음"을 잘못 보이지 않고, 더 보기가 옛 검색어를 쓰지 않게 */
  const [searchedQuery, setSearchedQuery] = useState('')
  const abortRef = useRef<AbortController | null>(null)

  function load(query: string, nextPage: number) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setLoading(true)
    setError(null)
    searchPublicLibraries(query, nextPage, PAGE_SIZE, controller.signal)
      .then(res => {
        if (abortRef.current !== controller) return
        setResults(prev => {
          if (nextPage === 0) return res.items
          const seen = new Set(prev.map(item => item.libCode))
          return [...prev, ...res.items.filter(item => !seen.has(item.libCode))]
        })
        setSearchedQuery(query)
        setPage(nextPage)
        setHasNext(res.hasNext)
      })
      .catch(err => {
        if (err.name !== 'AbortError' && abortRef.current === controller) {
          setError(toUserMessage(err, '도서관을 찾지 못했어요. 잠시 뒤 다시 시도해 주세요.'))
        }
      })
      .finally(() => {
        if (abortRef.current === controller) setLoading(false)
      })
  }

  // 입력이 멈추면 검색한다(글자마다 요청하지 않게)
  useEffect(() => {
    const query = keyword.trim()
    // 검색어가 바뀌면 진행 중인 요청(더 보기 포함)을 끊고 옛 검색어의 더 보기를 감춘다
    abortRef.current?.abort()
    setHasNext(false)
    setLoading(false)
    setError(null)
    if (!query) {
      abortRef.current?.abort()
      setResults([])
      setHasNext(false)
      setLoading(false)
      setError(null)
      return
    }
    const timer = setTimeout(() => load(query, 0), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword])

  useEffect(() => () => abortRef.current?.abort(), [])

  const savedCodes = new Set(libraries.map(l => l.libCode))
  const full = libraries.length >= MAX_PUBLIC_LIBRARIES

  function toggleSaved(item: { libCode: string; name: string }) {
    if (savedCodes.has(item.libCode)) onLibrariesChange(libraries.filter(l => l.libCode !== item.libCode))
    else if (!full) onLibrariesChange([...libraries, { libCode: item.libCode, name: item.name }])
  }

  function toggleReference(item: { libCode: string; name: string }) {
    onReferenceChange(reference?.libCode === item.libCode ? null : { libCode: item.libCode, name: item.name })
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <p className="text-meta font-semibold text-ink-700">
          내 도서관
          <span className="ml-1.5 text-ink-500">{libraries.length}/{MAX_PUBLIC_LIBRARIES}</span>
        </p>
      </div>
      {libraries.length === 0 ? (
        <p className="text-label text-ink-600 mb-3">자주 가는 도서관을 고르면 멀리 있어도 소장 여부를 바로 확인해 드려요</p>
      ) : (
        <div className="flex flex-wrap gap-1.5 mb-3">
          {libraries.map(lib => (
            <span key={lib.libCode} className="inline-flex items-center gap-1 pl-3 pr-1 py-1 rounded-full bg-accent-soft border border-accent/30 text-label text-accent-strong font-semibold">
              {lib.name}
              <ChipRemoveButton label={`${lib.name} 빼기`} onClick={() => toggleSaved(lib)} />
            </span>
          ))}
        </div>
      )}

      <div className="mb-3 p-3 rounded-xl bg-ink-50 text-label text-ink-700">
        {reference ? (
          <div className="flex items-center justify-between gap-2">
            <span>
              주변 도서관 기준: <strong className="text-ink-900">{reference.name}</strong>
            </span>
            <button type="button" onClick={() => onReferenceChange(null)} className="min-h-10 px-2 -mr-2 font-semibold text-primary hover:underline cursor-pointer shrink-0">
              해제
            </button>
          </div>
        ) : (
          <span>위치를 쓰지 않을 때는 도서관 하나를 기준으로 주변을 찾을 수 있어요(검색 결과에서 &lsquo;기준&rsquo;)</span>
        )}
      </div>

      <div className="relative mb-2">
        <SearchIcon size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" />
        <input
          type="search"
          placeholder="도서관 이름이나 주소(예: 용산)"
          aria-label="공공도서관 이름·주소 검색"
          value={keyword}
          maxLength={200}
          onChange={e => setKeyword(e.target.value)}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-ink-200 bg-white text-body
            focus:outline-none focus:ring-4 focus:ring-accent/30 focus:border-primary transition placeholder:text-ink-500"
        />
      </div>

      {error && <p role="alert" className="text-meta text-status-danger mb-2">{error}</p>}

      {keyword.trim() && (
        <div className="max-h-64 overflow-y-auto scrollbar-thin bg-white rounded-2xl border border-ink-100 p-1" aria-busy={loading}>
          {!loading && !error && results.length === 0 && searchedQuery === keyword.trim() && (
            <p className="text-meta text-ink-600 text-center py-4">맞는 도서관이 없어요</p>
          )}
          {results.map(item => {
            const saved = savedCodes.has(item.libCode)
            const isReference = reference?.libCode === item.libCode
            return (
              <div key={item.libCode} className="flex items-center gap-2 px-3 py-2 rounded-xl hover:bg-ink-50">
                <div className="flex-1 min-w-0">
                  <p className="text-body text-ink-900 truncate">{item.name}</p>
                  {item.address && <p className="text-label text-ink-500 truncate">{item.address}</p>}
                </div>
                <button
                  type="button"
                  onClick={() => toggleReference(item)}
                  aria-pressed={isReference}
                  aria-label={`${item.name} 주변 도서관 기준으로 정하기`}
                  className={`min-h-10 px-3 rounded-lg text-label font-semibold border transition cursor-pointer shrink-0
                    ${isReference ? 'bg-ink-900 text-white border-ink-900' : 'border-ink-200 text-ink-700 hover:border-ink-400'}`}
                >
                  기준
                </button>
                <button
                  type="button"
                  onClick={() => toggleSaved(item)}
                  disabled={!saved && full}
                  aria-pressed={saved}
                  aria-label={saved ? `${item.name} 내 도서관에서 빼기` : `${item.name} 내 도서관에 추가`}
                  className={`min-h-10 px-3 rounded-lg text-label font-semibold transition cursor-pointer shrink-0
                    disabled:opacity-40 disabled:cursor-not-allowed
                    ${saved ? 'bg-accent-soft text-accent-strong border border-accent/30' : 'bg-primary text-white hover:bg-primary-dark'}`}
                >
                  {saved ? '추가됨' : '추가'}
                </button>
              </div>
            )
          })}
          {loading && (
            <div className="flex justify-center py-3">
              <SpinnerIcon size={20} strokeWidth={2.2} className="text-ink-400" />
            </div>
          )}
          {!loading && hasNext && searchedQuery === keyword.trim() && (
            <button
              type="button"
              onClick={() => load(searchedQuery, page + 1)}
              className="w-full min-h-11 text-meta font-semibold text-primary hover:underline cursor-pointer"
            >
              더 보기
            </button>
          )}
        </div>
      )}
      {full && keyword.trim() && (
        <p className="text-label text-ink-600 mt-2">내 도서관은 {MAX_PUBLIC_LIBRARIES}곳까지예요. 다른 곳을 추가하려면 하나를 빼 주세요</p>
      )}
    </div>
  )
}
