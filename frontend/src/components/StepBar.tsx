const STEPS = ['책 검색', '책 선택', '통합 조회', '전자도서관']

export function StepBar({ current }: { current: number }) {
  return (
    <div className="flex items-center gap-0 mb-8">
      {STEPS.map((label, i) => {
        const done = i < current
        const active = i === current
        return (
          <div key={i} className="flex items-center">
            <div className="flex flex-col items-center">
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-semibold transition-colors
                  ${done ? 'bg-primary text-white' : active ? 'bg-primary text-white ring-4 ring-blue-100' : 'bg-slate-200 text-slate-500'}`}
              >
                {done ? (
                  <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                    <path d="M2 7l4 4 6-6" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                ) : (
                  i + 1
                )}
              </div>
              <span
                className={`mt-1 text-xs whitespace-nowrap ${active ? 'text-primary font-semibold' : done ? 'text-slate-500' : 'text-slate-400'}`}
              >
                {label}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <div className={`h-0.5 w-12 sm:w-20 mx-1 mt-[-12px] transition-colors ${done ? 'bg-primary' : 'bg-slate-200'}`} />
            )}
          </div>
        )
      })}
    </div>
  )
}
