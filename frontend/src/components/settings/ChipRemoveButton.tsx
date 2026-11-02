/** 선택 칩의 해제(×) 버튼 */
export function ChipRemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-7 h-7 rounded-full hover:bg-accent/20 flex items-center justify-center transition cursor-pointer shrink-0"
      aria-label={label}
    >
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true">
        <path d="M18 6L6 18M6 6l12 12" />
      </svg>
    </button>
  )
}
