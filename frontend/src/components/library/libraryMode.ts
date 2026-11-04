import type { KeyValueStore } from '../../preferences/preferences.ts'

/**
 * 공공도서관 칸의 보기 모드와 위치 자동 요청 판단(2026-11-04 사용자 결정).
 * - 마지막에 고른 보기 모드(내 도서관/주변)만 이 브라우저에 기억한다. 위치 좌표는 저장하지 않는다.
 * - 위치는 브라우저 권한이 이미 '허용'일 때만 버튼 없이 받는다. 권한을 묻는 팝업을 먼저 띄우지 않는다.
 */
export type LibraryMode = 'saved' | 'nearby'

const MODE_KEY = 'checkbook.library.mode'

export function readLastMode(store: KeyValueStore | null): LibraryMode | null {
  try {
    const value = store?.getItem(MODE_KEY)
    return value === 'saved' || value === 'nearby' ? value : null
  } catch {
    return null // 사생활 보호 모드 등에서 저장소 접근이 막히면 기억 없음
  }
}

export function writeLastMode(store: KeyValueStore | null, mode: LibraryMode): void {
  try {
    store?.setItem(MODE_KEY, mode)
  } catch {
    // 기억하지 못해도 화면은 그대로 동작한다
  }
}

/** 처음 열 모드. 주변을 마지막으로 봤으면 주변, 아니면 저장한 도서관이 있을 때 내 도서관 */
export function initialMode(savedCount: number, lastMode: LibraryMode | null): LibraryMode {
  if (lastMode === 'nearby') return 'nearby'
  return savedCount > 0 ? 'saved' : 'nearby'
}

export type PermissionStateLike = 'granted' | 'prompt' | 'denied' | null

/** 버튼 없이 위치를 받아도 되는가 — 이미 허용됐고, 주변 모드인데 기준점이 없고, 받는 중이 아닐 때만 */
export function shouldAutoLocate({ permission, mode, hasOrigin, loading }: {
  permission: PermissionStateLike
  mode: LibraryMode
  hasOrigin: boolean
  loading: boolean
}): boolean {
  return permission === 'granted' && mode === 'nearby' && !hasOrigin && !loading
}
