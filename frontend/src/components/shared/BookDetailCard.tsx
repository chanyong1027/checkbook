import type { BookCandidate } from '../../types'

export function BookDetailCard({ book }: { book: BookCandidate }) {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 mb-5 shadow-sm shadow-orange-50">
      <div className="flex gap-4">
        {book.coverUrl ? (
          <img
            src={book.coverUrl}
            alt={book.title}
            className="w-[72px] h-[100px] object-cover rounded-xl shadow-md shadow-orange-100 shrink-0"
          />
        ) : (
          <div className="w-[72px] h-[100px] bg-orange-50 rounded-xl shrink-0 flex items-center justify-center">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#fdba74" strokeWidth="1.5">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
          </div>
        )}
        <div className="flex-1 min-w-0">
          <h2 className="font-bold text-slate-800 text-base leading-snug mb-1 line-clamp-2">
            {book.title}
          </h2>
          <p className="text-sm text-slate-500 truncate">{book.author}</p>
          <p className="text-xs text-slate-400 truncate mt-0.5">{book.publisher}</p>
          {book.publishedAt && (
            <p className="text-xs text-slate-300 mt-0.5">{book.publishedAt}</p>
          )}
          <span className="mt-2 inline-block text-xs font-mono bg-orange-50 text-orange-400 px-2 py-0.5 rounded-lg">
            {book.isbn13}
          </span>
        </div>
      </div>
    </div>
  )
}
