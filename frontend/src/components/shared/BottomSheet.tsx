import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { CloseIcon } from './icons'
import { useBodyScrollLock } from './useBodyScrollLock'

interface BottomSheetProps {
  open: boolean
  onClose: () => void
  title?: string
  children: React.ReactNode
}

export function BottomSheet({ open, onClose, title, children }: BottomSheetProps) {
  const sheetRef = useRef<HTMLDivElement>(null)
  useBodyScrollLock(open)

  // 열리면 시트 안으로 포커스를 옮기고, 닫히면 열기 전 자리로 돌려준다
  useEffect(() => {
    if (!open) return
    const opener = document.activeElement as HTMLElement | null
    sheetRef.current?.focus()
    return () => opener?.focus?.()
  }, [open])

  // Escape로 닫기(닫기 버튼과 같은 동작 — 변경분이 있으면 부모가 확인 대화창으로 막는다)
  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      // 위에 뜬 확인 대화창이 먼저 처리했으면(preventDefault) 건드리지 않는다
      if (e.key === 'Escape' && !e.defaultPrevented) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  // body에 바로 그린다 — 조상에 transform이 있으면 fixed가 화면이 아니라 그 조상 기준으로 놓인다
  return createPortal(
    <div className="fixed inset-0 z-50">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/40 sheet-backdrop"
        onClick={onClose}
      />
      {/* Sheet */}
      <div
        ref={sheetRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="absolute bottom-0 left-0 right-0 mx-auto w-full max-w-2xl bg-white rounded-t-2xl sheet-slide-up max-h-[85vh] flex flex-col focus:outline-none"
      >
        {/* Handle + title */}
        <div className="flex items-center justify-between px-5 pt-3 pb-2 border-b border-ink-100 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-1 bg-ink-200 rounded-full" />
            {title && <h3 className="text-body font-bold text-ink-900">{title}</h3>}
          </div>
          <button
            onClick={onClose}
            className="w-11 h-11 -mr-3 rounded-lg flex items-center justify-center text-ink-500 hover:bg-ink-50 hover:text-ink-900 transition cursor-pointer"
            aria-label="닫기"
          >
            <CloseIcon size={18} strokeWidth={2} />
          </button>
        </div>
        {/* Content — iOS 홈 인디케이터 영역 보호 */}
        <div
          className="flex-1 overflow-y-auto scrollbar-thin px-5 pt-4"
          style={{ paddingBottom: 'calc(1rem + env(safe-area-inset-bottom))' }}
        >
          {children}
        </div>
      </div>
    </div>,
    document.body,
  )
}
