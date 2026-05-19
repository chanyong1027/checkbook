import { useState, useEffect } from 'react'
import type { BookCandidate } from './types'
import { BookSearchStep } from './components/BookSearchStep'
import { BookDetailPage } from './components/BookDetailPage'

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
      const next = parseRoute(window.location.pathname)
      const stateBook = (window.history.state as { book?: unknown } | null)?.book
      const restored = isBookCandidate(stateBook) ? stateBook : null
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
    if (selectedBook) {
      sessionStorage.setItem(BOOK_FALLBACK_KEY, JSON.stringify(selectedBook))
    } else {
      sessionStorage.removeItem(BOOK_FALLBACK_KEY)
    }
  }, [selectedBook])

  function goToDetail(book: BookCandidate) {
    window.history.pushState(
      { seeded: true, book },
      '',
      `/book/${encodeURIComponent(book.isbn13)}`,
    )
    setDirection('forward')
    setSelectedBook(book)
    setRoute({ step: 'detail', isbn: book.isbn13 })
  }

  function handleReset() {
    sessionStorage.removeItem(SEARCH_CACHE_KEY)
    setResetKey(k => k + 1)
    window.history.pushState({ seeded: true }, '', '/')
    setDirection('back')
    setSelectedBook(null)
    setRoute({ step: 'search', isbn: null })
  }

  return (
    <div className="min-h-dvh bg-surface flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-white/90 backdrop-blur-sm border-b border-orange-100 px-4 h-14 flex items-center gap-3">
        {/* Back button — only on detail step */}
        {route.step === 'detail' && (
          <button
            onClick={() => window.history.back()}
            className="p-2 -ml-2 rounded-xl hover:bg-orange-50 transition cursor-pointer"
            aria-label="뒤로"
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#475569" strokeWidth="2.5" strokeLinecap="round">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
        )}

        <button
          onClick={handleReset}
          className="flex items-center gap-2 cursor-pointer"
          aria-label="홈으로"
        >
          <div className="w-8 h-8 bg-primary rounded-xl flex items-center justify-center shadow-sm shadow-orange-200">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
          </div>
          <span className="font-bold text-slate-800 text-[15px] tracking-tight">CheckBook</span>
        </button>
      </header>

      {/* Main */}
      <main className="flex-1 flex justify-center px-4 py-5 overflow-x-hidden">
        <div className="w-full max-w-sm">
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

            {route.step === 'detail' && (
              <BookDetailPage
                key={route.isbn}
                isbn13={route.isbn}
                initialBook={selectedBook}
                onReset={handleReset}
              />
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
