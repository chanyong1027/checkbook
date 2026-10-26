import { useEffect } from 'react'

// 겹쳐 뜨는 시트·대화창이 각자 body 스크롤을 잠갔다 풀면, 둘이 한 번에 닫힐 때 정리 순서에 따라
// 잠긴 값('hidden')이 남을 수 있다 — 잠금 수를 세어 마지막 하나가 풀릴 때만 원래 값으로 되돌린다
let lockCount = 0
let savedOverflow = ''

export function useBodyScrollLock(active: boolean) {
  useEffect(() => {
    if (!active) return
    if (lockCount === 0) {
      savedOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
    }
    lockCount += 1
    return () => {
      lockCount -= 1
      if (lockCount === 0) document.body.style.overflow = savedOverflow
    }
  }, [active])
}
