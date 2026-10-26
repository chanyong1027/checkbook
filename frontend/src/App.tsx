import { useState, useEffect, useRef } from 'react'
import type { BookCandidate } from './types'
import { BookSearchStep } from './components/BookSearchStep'
import { BookDetailPage } from './components/BookDetailPage'
import { BookIcon, ChevronLeftIcon } from './components/shared/icons'

const SEARCH_CACHE_KEY = 'cb_search_cache'
const BOOK_FALLBACK_KEY = 'cb_book'

type Route =
  | { step: 'search'; isbn: null }
  | { step: 'detail'; isbn: string }

function isBookCandidate(value: unknown): value is BookCandidate {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return (
    typeof v.title === 'string' &&
    typeof v.author === 'string' &&
    typeof v.publisher === 'string' &&
    typeof v.isbn13 === 'string' &&
    typeof v.coverUrl === 'string' &&
    typeof v.publishedAt === 'string'
  )
}

function parseRoute(pathname: string): Route {
  const m = pathname.match(/^\/book\/(\d{13})$/)
  if (m) return { step: 'detail', isbn: m[1] }
  return { step: 'search', isbn: null }
}

function loadBookFromState(isbn: string): BookCandidate | null {
  const stateBook = (window.history.state as { book?: unknown } | null)?.book
  if (isBookCandidate(stateBook) && stateBook.isbn13 === isbn) return stateBook

  // sessionStorage fallback (refresh-resilience)
  try {
    const raw = sessionStorage.getItem(BOOK_FALLBACK_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    if (isBookCandidate(parsed) && parsed.isbn13 === isbn) return parsed
  } catch {
    /* ignore */
  }
  return null
}

export default function App() {
  const initialRoute = parseRoute(window.location.pathname)
  const [route, setRoute] = useState<Route>(initialRoute)
  const [direction, setDirection] = useState<'forward' | 'back'>('forward')
  const [selectedBook, setSelectedBook] = useState<BookCandidate | null>(
    initialRoute.step === 'detail' ? loadBookFromState(initialRoute.isbn) : null,
  )
  const [resetKey, setResetKey] = useState(0)
  // 마지막으로 그린 경로. 같은 문서 안의 해시 이동(#library 등)도 popstate를 내므로 경로가 같으면 무시한다
  const pathRef = useRef(window.location.pathname)

  // Seed back stack on cold detail entry so the browser back button returns to search
  useEffect(() => {
    if (initialRoute.step !== 'detail') return
    const seeded = (window.history.state as { seeded?: boolean } | null)?.seeded === true
    if (seeded) return

    const currentUrl = window.location.pathname + window.location.search
    window.history.replaceState({ seeded: true }, '', '/')
    window.history.pushState({ seeded: true, book: selectedBook }, '', currentUrl)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // popstate: URL 변경 시 React state 재동기화
  useEffect(() => {
    function onPop() {
      if (window.location.pathname === pathRef.current) return
      pathRef.current = window.location.pathname
      const next = parseRoute(window.location.pathname)
      const stateBook = (window.history.state as { book?: unknown } | null)?.book
      const restored =
        next.step === 'detail' && isBookCandidate(stateBook) && stateBook.isbn13 === next.isbn
          ? stateBook
          : null
      setDirection('back')
      setRoute(next)
      setSelectedBook(
        next.step === 'detail' ? (restored ?? loadBookFromState(next.isbn)) : null,
      )
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  // selectedBook → sessionStorage 동기화 (history.state가 비어 있을 때 fallback)
  useEffect(() => {
    try {
      if (selectedBook) {
        sessionStorage.setItem(BOOK_FALLBACK_KEY, JSON.stringify(selectedBook))
      } else {
        sessionStorage.removeItem(BOOK_FALLBACK_KEY)
      }
    } catch {
      // Safari private mode·quota 초과·storage 차단 등 → 무시 (history.state 가 1차 소스)
    }
  }, [selectedBook])

  function goToDetail(book: BookCandidate) {
    window.history.pushState(
      { seeded: true, book },
      '',
      `/book/${encodeURIComponent(book.isbn13)}`,
    )
    pathRef.current = window.location.pathname
    setDirection('forward')
    setSelectedBook(book)
    setRoute({ step: 'detail', isbn: book.isbn13 })
  }

  function handleReset() {
    try {
      sessionStorage.removeItem(SEARCH_CACHE_KEY)
    } catch {
      // 저장소를 막은 브라우저 — 지울 캐시도 없다
    }
    setResetKey(k => k + 1)
    window.history.pushState({ seeded: true }, '', '/')
    pathRef.current = '/'
    setDirection('back')
    setSelectedBook(null)
    setRoute({ step: 'search', isbn: null })
  }

  return (
    <div className="min-h-dvh bg-surface flex flex-col">
      <header
        className="sticky top-0 z-10 bg-white/95 backdrop-blur-sm border-b border-ink-100"
        style={{ paddingTop: 'env(safe-area-inset-top)' }}
      >
        <div className="mx-auto max-w-content px-4 md:px-6 h-14 md:h-16 flex items-center gap-2">
          {route.step === 'detail' && (
            <button
              onClick={() => window.history.back()}
              className="w-11 h-11 -ml-3 rounded-xl flex items-center justify-center text-ink-700 hover:bg-ink-50 transition cursor-pointer"
              aria-label="뒤로"
            >
              <ChevronLeftIcon size={22} strokeWidth={2} />
            </button>
          )}

          <button
            onClick={handleReset}
            className="flex items-center gap-2 min-h-11 cursor-pointer"
            aria-label="홈으로"
          >
            <span className="w-8 h-8 bg-accent rounded-[9px] flex items-center justify-center text-white">
              <BookIcon size={17} strokeWidth={2.2} />
            </span>
            <span className="font-bold text-ink-900 text-body-lg tracking-tight">CheckBook</span>
          </button>
        </div>
      </header>

      {/* overflow-x-hidden은 스크롤 컨테이너를 만들어 상세 책 패널의 sticky가 안 먹는다 — clip은 넘침만 자른다.
          clip을 모르는 브라우저(Safari 16 미만)는 hidden으로(그때는 패널이 고정되지 않을 뿐) */}
      <main className="flex-1 overflow-x-hidden supports-[overflow:clip]:overflow-x-clip">
        <div
          key={route.step}
          className={direction === 'forward' ? 'slide-in-right' : 'slide-in-left'}
        >
          {route.step === 'search' && (
            <BookSearchStep
              key={resetKey}
              onSelect={goToDetail}
            />
          )}

          {/* 폭·여백은 상세 화면이 정한다(모바일은 섹션을 화면 끝까지, PC는 책 패널 + 본문 2단) */}
          {route.step === 'detail' && (
            <BookDetailPage
              key={route.isbn}
              isbn13={route.isbn}
              initialBook={selectedBook}
              onReset={handleReset}
            />
          )}
        </div>
      </main>
    </div>
  )
}
