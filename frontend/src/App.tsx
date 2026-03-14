import { useState, useEffect } from 'react'
import type { BookCandidate } from './types'
import { BookSearchStep } from './components/BookSearchStep'
import { ELibraryStep } from './components/ELibraryStep'
import { PricesStep } from './components/PricesStep'

type Step = 'search' | 'elibrary' | 'prices'

const STEP_ORDER: Step[] = ['search', 'elibrary', 'prices']

function loadSession(): { step: Step; book: BookCandidate | null } {
  try {
    const step = (sessionStorage.getItem('cb_step') as Step) || 'search'
    const raw = sessionStorage.getItem('cb_book')
    const book: BookCandidate | null = raw ? JSON.parse(raw) : null
    // elibrary/prices 스텝인데 book이 없으면 search로 fallback
    if (step !== 'search' && !book) return { step: 'search', book: null }
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
    goBack('search')
  }

  const stepIdx = STEP_ORDER.indexOf(step)

  return (
    <div className="min-h-dvh bg-surface flex flex-col">
      {/* Header */}
      <header className="sticky top-0 z-10 bg-white/90 backdrop-blur-sm border-b border-orange-100 px-4 h-14 flex items-center">
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

        {/* Step dots */}
        <div className="ml-auto flex items-center gap-1.5">
          {STEP_ORDER.map((s, i) => (
            <div
              key={s}
              className={`rounded-full transition-all duration-300 ${
                i === stepIdx
                  ? 'w-5 h-2 bg-primary'
                  : i < stepIdx
                  ? 'w-2 h-2 bg-orange-300'
                  : 'w-2 h-2 bg-slate-200'
              }`}
            />
          ))}
        </div>
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
                onSelect={book => {
                  setSelectedBook(book)
                  goForward('elibrary')
                }}
              />
            )}

            {step === 'elibrary' && selectedBook && (
              <ELibraryStep
                book={selectedBook}
                onNext={() => goForward('prices')}
                onBack={() => goBack('search')}
                onReset={handleReset}
              />
            )}

            {step === 'prices' && selectedBook && (
              <PricesStep
                book={selectedBook}
                onBack={() => goBack('elibrary')}
                onReset={handleReset}
              />
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
