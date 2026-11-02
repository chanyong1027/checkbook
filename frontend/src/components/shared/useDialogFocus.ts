import { useEffect, type RefObject } from 'react'

let openDialogs = 0
let previousRootInert = false

// 겹쳐 열린 시트·확인창 중 맨 위 대화창만 Tab을 처리한다.
export function useDialogFocus(
  open: boolean,
  dialogRef: RefObject<HTMLElement>,
  initialFocusRef?: RefObject<HTMLElement>,
) {
  useEffect(() => {
    if (!open) return
    const dialog = dialogRef.current
    if (!dialog) return
    const opener = document.activeElement as HTMLElement | null
    const root = document.getElementById('root')
    if (openDialogs++ === 0 && root) {
      previousRootInert = root.inert
      root.inert = true
    }
    const initialFocus = initialFocusRef?.current ?? dialog
    initialFocus.focus()

    function trapTab(event: KeyboardEvent) {
      if (event.key !== 'Tab' || !dialog) return
      const dialogs = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]'))
      if (dialogs[dialogs.length - 1] !== dialog) return
      const elements = Array.from(dialog.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )).filter(element => element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden')
      const first = elements[0]
      const last = elements[elements.length - 1]
      const focused = document.activeElement
      if (!first) {
        event.preventDefault()
        dialog.focus()
      } else if (event.shiftKey && (focused === first || focused === dialog || !dialog.contains(focused))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (focused === last || focused === dialog || !dialog.contains(focused))) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', trapTab, true)
    return () => {
      window.removeEventListener('keydown', trapTab, true)
      if (--openDialogs === 0 && root) root.inert = previousRootInert
      // 두 대화창이 함께 닫히면 모든 정리와 배경 inert 해제가 끝난 뒤 복귀한다.
      queueMicrotask(() => {
        if (!opener?.isConnected) return
        const dialogs = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]'))
        const topDialog = dialogs[dialogs.length - 1]
        if (!topDialog || topDialog.contains(opener)) opener.focus({ preventScroll: true })
      })
    }
  }, [open, dialogRef, initialFocusRef])
}
