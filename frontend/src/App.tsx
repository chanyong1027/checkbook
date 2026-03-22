import { useState, useEffect } from 'react'
import type { BookCandidate } from './types'
import { BookSearchStep } from './components/BookSearchStep'
import { BookDetailPage } from './components/BookDetailPage'

type Step = 'search' | 'detail'

const SEARCH_CACHE_KEY = 'cb_search_cache'

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

function loadSession(): { step: Step; book: BookCandidate | null } {
  try {
    const raw = sessionStorage.getItem('cb_step')
    // Unknown steps (legacy 'elibrary', 'prices') fallback to search
    const step: Step = raw === 'detail' ? 'detail' : 'search'
    const bookRaw = sessionStorage.getItem('cb_book')
    const parsed = bookRaw ? JSON.parse(bookRaw) : null
    const book: BookCandidate | null = isBookCandidate(parsed) ? parsed : null
    if (step === 'detail' && !book) return { step: 'search', book: null }
    return { step, book }
  } catch {
    return { step: 'search', book: null }
  }
}

export default function App() {
  const initial = loadSession()
  const [step, setStep] = useState<Step>(initial.step)
  const [direction, setDirection] = useState<'forward' | 'back'>('forward')
  const [selectedBook, setSelectedBook] = useState<BookCandidate | null>(initial.book)
  const [resetKey, setResetKey] = useState(0)

  useEffect(() => {
    sessionStorage.setItem('cb_step', step)
  }, [step])

  useEffect(() => {
    if (selectedBook) sessionStorage.setItem('cb_book', JSON.stringify(selectedBook))
    else sessionStorage.removeItem('cb_book')
  }, [selectedBook])

  function goForward(newStep: Step) {
    setDirection('forward')
    setStep(newStep)
  }

  function goBack(newStep: Step) {
    setDirection('back')
    setStep(newStep)
  }

  function handleReset() {
    setSelectedBook(null)
    sessionStorage.removeItem(SEARCH_CACHE_KEY)
    setResetKey(k => k + 1)
    goBack('search')
  }

  return (
    <div className="min-h-dvh bg-surface flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-white/90 backdrop-blur-sm border-b border-orange-100 px-4 h-14 flex items-center gap-3">
        {/* Back button — only on detail step */}
        {step === 'detail' && (
          <button
            onClick={() => goBack('search')}
            className="p-2 -ml-2 rounded-xl hover:bg-orange-50 transition cursor-pointer"
            aria-label="뒤로"
          >
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" strokeWidth="2.5" strokeLinecap="round">
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
            key={step}
            className={direction === 'forward' ? 'slide-in-right' : 'slide-in-left'}
          >
            {step === 'search' && (
              <BookSearchStep
                key={resetKey}
                onSelect={book => {
                  setSelectedBook(book)
                  goForward('detail')
                }}
              />
            )}

            {step === 'detail' && selectedBook && (
              <BookDetailPage
                key={selectedBook.isbn13}
                book={selectedBook}
                onReset={handleReset}
              />
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
