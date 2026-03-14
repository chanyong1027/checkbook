import { useState } from 'react'
import type { BookCandidate } from './types'
import { StepBar } from './components/StepBar'
import { BookSearchStep } from './components/BookSearchStep'
import { SearchResultStep } from './components/SearchResultStep'
import { ELibraryStep } from './components/ELibraryStep'

type Step = 'search' | 'result' | 'elibrary'

export default function App() {
  const [step, setStep] = useState<Step>('search')
  const [selectedBook, setSelectedBook] = useState<BookCandidate | null>(null)
  const [isbn13ForElib, setIsbn13ForElib] = useState('')
  const [titleForElib, setTitleForElib] = useState('')

  const stepIndex: Record<Step, number> = { search: 0, result: 2, elibrary: 3 }

  function handleBookSelect(book: BookCandidate) {
    setSelectedBook(book)
    setStep('result')
  }

  function handleGoELib(isbn13: string, title: string) {
    setIsbn13ForElib(isbn13)
    setTitleForElib(title)
    setStep('elibrary')
  }

  function handleReset() {
    setSelectedBook(null)
    setIsbn13ForElib('')
    setTitleForElib('')
    setStep('search')
  }

  return (
    <div className="min-h-dvh bg-slate-50 flex flex-col">
      {/* Header */}
      <header className="bg-white border-b border-slate-100 px-6 py-4 flex items-center gap-3">
        <button onClick={handleReset} className="flex items-center gap-2 cursor-pointer group">
          <div className="w-8 h-8 bg-primary rounded-lg flex items-center justify-center shadow-sm">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
          </div>
          <span className="font-bold text-slate-900 text-lg group-hover:text-primary transition">CheckBook</span>
        </button>
        <span className="text-xs text-slate-400 font-mono ml-auto">dev</span>
      </header>

      {/* Main */}
      <main className="flex-1 flex items-start justify-center px-4 py-8">
        <div className="w-full max-w-2xl">
          <StepBar current={stepIndex[step]} />

          <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 sm:p-8">
            {step === 'search' && (
              <BookSearchStep onSelect={handleBookSelect} />
            )}

            {step === 'result' && selectedBook && (
              <SearchResultStep
                book={selectedBook}
                onNext={handleGoELib}
                onBack={handleReset}
              />
            )}

            {step === 'elibrary' && (
              <ELibraryStep
                isbn13={isbn13ForElib}
                bookTitle={titleForElib}
                onBack={() => setStep('result')}
                onReset={handleReset}
              />
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
