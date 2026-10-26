import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useBodyScrollLock } from './useBodyScrollLock'

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

  useBodyScrollLock(open)

  // 초기 포커스(취소 — 안전한 기본값)
  useEffect(() => {
    if (!open) return
    // 다음 tick 에 포커스 (sheet 위에 떠 있는 다이얼로그라서)
    const t = setTimeout(() => cancelBtnRef.current?.focus(), 0)
    return () => clearTimeout(t)
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
    // 캡처 단계로 받아 아래 깔린 시트보다 먼저 처리한다(시트는 preventDefault된 Escape를 무시)
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [open, onCancel])

  if (!open) return null

  // body에 바로 그린다 — 조상에 transform이 있으면 fixed가 화면이 아니라 그 조상 기준으로 놓인다
  return createPortal(
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
                ? 'bg-status-danger hover:bg-[#912018] shadow-none'
                : 'bg-primary hover:bg-primary-dark shadow-none')
            }
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
