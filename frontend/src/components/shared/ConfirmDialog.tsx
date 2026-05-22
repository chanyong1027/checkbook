import { useEffect, useRef } from 'react'

interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmLabel: string
  cancelLabel: string
  destructive?: boolean
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  cancelLabel,
  destructive = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const cancelBtnRef = useRef<HTMLButtonElement>(null)

  // Body scroll lock + 초기 포커스(취소 — 안전한 기본값)
  useEffect(() => {
    if (!open) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    // 다음 tick 에 포커스 (sheet 위에 떠 있는 다이얼로그라서)
    const t = setTimeout(() => cancelBtnRef.current?.focus(), 0)
    return () => {
      document.body.style.overflow = prev
      clearTimeout(t)
    }
  }, [open])

  // Escape 로 닫기 (계속 편집 == 취소 동작)
  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onCancel()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center px-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      aria-describedby="confirm-dialog-message"
    >
      {/* Backdrop (클릭하면 취소 동작) */}
      <div
        className="absolute inset-0 bg-black/50 sheet-backdrop"
        onClick={onCancel}
      />

      {/* Dialog */}
      <div className="relative w-full max-w-sm bg-white rounded-2xl shadow-xl p-5 sheet-slide-up">
        <h3
          id="confirm-dialog-title"
          className="text-base font-bold text-slate-900 mb-1.5"
        >
          {title}
        </h3>
        <p
          id="confirm-dialog-message"
          className="text-sm text-slate-600 mb-5 leading-relaxed"
        >
          {message}
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            ref={cancelBtnRef}
            onClick={onCancel}
            className="flex-1 py-2.5 rounded-2xl text-sm font-semibold text-slate-700
              bg-slate-100 hover:bg-slate-200 active:scale-[0.98] transition cursor-pointer"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className={
              'flex-1 py-2.5 rounded-2xl text-sm font-semibold text-white active:scale-[0.98] transition cursor-pointer shadow-sm ' +
              (destructive
                ? 'bg-red-500 hover:bg-red-600 shadow-red-200'
                : 'bg-primary hover:bg-primary-dark shadow-orange-200')
            }
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
