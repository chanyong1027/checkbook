/**
 * 내 도서관 설정(D7-1). 로그인 없이 이 브라우저의 localStorage에 둔다 — 다른 기기와 동기화되지 않는다.
 * 순수 함수만 둬서 저장소 없이(node --test) 시험한다. React 상태 연결은 PreferencesContext.
 */

export const PREFERENCES_KEY = 'checkbook_preferences_v1'
/** 이전 화면이 전자도서관 선택(최대 20곳)을 두던 키. 새 형식으로 옮긴 뒤 지운다 */
export const LEGACY_ELIB_KEY = 'checkbook_elib_ids'
export const MAX_PUBLIC_LIBRARIES = 5
export const MAX_ELIBRARIES = 5

/** 이름은 칩 표시용 사본이다. 조회는 코드로만 하고, 기관 정보의 기준은 서버 디렉터리다 */
export interface SavedPublicLibrary {
  libCode: string
  name: string
}

export interface Preferences {
  version: 1
  publicLibraries: SavedPublicLibrary[]
  eLibraryIds: number[]
  /** GPS 대신 주변 검색의 기준으로 쓸 기관. 즐겨찾기(publicLibraries)와 별개 */
  referenceLibrary: SavedPublicLibrary | null
}

export const EMPTY_PREFERENCES: Preferences = {
  version: 1,
  publicLibraries: [],
  eLibraryIds: [],
  referenceLibrary: null,
}

export interface LoadedPreferences {
  preferences: Preferences
  /** 옛 선택이 5곳을 넘어 아직 옮기지 못한 전자도서관 ID. 사용자가 5곳을 고를 때까지 자동 검색을 멈춘다 */
  pendingELibraryIds: number[] | null
}

/** localStorage의 필요한 부분. 시험에서 가짜로 바꾼다 */
export interface KeyValueStore {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

function toLibrary(value: unknown): SavedPublicLibrary | null {
  if (!value || typeof value !== 'object') return null
  const v = value as Record<string, unknown>
  if (typeof v.libCode !== 'string' || typeof v.name !== 'string') return null
  const libCode = v.libCode.trim()
  if (libCode.length === 0 || libCode.length > 20) return null
  return { libCode, name: v.name }
}

function uniqueIds(value: unknown): number[] {
  if (!Array.isArray(value)) return []
  const ids = value.filter((id): id is number => Number.isInteger(id) && id > 0)
  return [...new Set(ids)]
}

/** 저장된 값을 검증해 정리한다. 형식이 아예 다르거나 모르는 버전이면 null(덮어쓰지 않고 기본값으로 시작) */
export function normalizePreferences(raw: unknown): Preferences | null {
  if (!raw || typeof raw !== 'object') return null
  const v = raw as Record<string, unknown>
  if (v.version !== 1) return null
  const seen = new Set<string>()
  const publicLibraries = (Array.isArray(v.publicLibraries) ? v.publicLibraries : [])
    .map(toLibrary)
    .filter((lib): lib is SavedPublicLibrary => {
      if (!lib || seen.has(lib.libCode)) return false
      seen.add(lib.libCode)
      return true
    })
    .slice(0, MAX_PUBLIC_LIBRARIES)
  return {
    version: 1,
    publicLibraries,
    eLibraryIds: uniqueIds(v.eLibraryIds).slice(0, MAX_ELIBRARIES),
    referenceLibrary: toLibrary(v.referenceLibrary),
  }
}

/**
 * 설정을 읽는다. 순서: 새 키 → (없으면) 옛 전자도서관 키 이관.
 * - 새 키가 손상됐으면 기본값으로 시작하되 원문은 지우지 않는다(사용자가 저장할 때만 덮어씀).
 * - 옛 선택이 5곳 이하면 새 형식으로 저장하고, 저장에 성공했을 때만 옛 키를 지운다.
 * - 5곳을 넘으면 몰래 자르지 않는다 — pendingELibraryIds로 넘겨 사용자가 고르게 한다.
 * - 저장소를 막은 브라우저(store가 null이거나 던짐)는 기본값.
 */
export function loadPreferences(store: KeyValueStore | null): LoadedPreferences {
  const empty: LoadedPreferences = { preferences: EMPTY_PREFERENCES, pendingELibraryIds: null }
  if (!store) return empty
  try {
    const raw = store.getItem(PREFERENCES_KEY)
    if (raw != null) {
      let parsed: unknown = null
      try {
        parsed = JSON.parse(raw)
      } catch {
        return empty
      }
      const normalized = normalizePreferences(parsed)
      return normalized ? { preferences: normalized, pendingELibraryIds: null } : empty
    }

    const legacyRaw = store.getItem(LEGACY_ELIB_KEY)
    if (legacyRaw == null) return empty
    let legacy: number[] = []
    try {
      legacy = uniqueIds(JSON.parse(legacyRaw))
    } catch {
      return empty
    }
    if (legacy.length > MAX_ELIBRARIES) {
      return { preferences: EMPTY_PREFERENCES, pendingELibraryIds: legacy }
    }
    const migrated: Preferences = { ...EMPTY_PREFERENCES, eLibraryIds: legacy }
    savePreferences(store, migrated)
    return { preferences: migrated, pendingELibraryIds: null }
  } catch {
    return empty
  }
}

/** 저장에 성공하면 true. 실패(용량·차단)면 false — 화면은 "이번 이용에만 적용"을 알린다 */
export function savePreferences(store: KeyValueStore | null, preferences: Preferences): boolean {
  if (!store) return false
  try {
    store.setItem(PREFERENCES_KEY, JSON.stringify(preferences))
  } catch {
    return false
  }
  try {
    // 새 값이 저장된 뒤에만 옛 키를 지운다(실패해도 다음 읽기는 새 키가 먼저라 무해)
    store.removeItem(LEGACY_ELIB_KEY)
  } catch {
    /* 무시 */
  }
  return true
}

/** 브라우저 localStorage. 접근만 해도 던지는 환경(일부 사생활 보호 모드)이 있어 감싼다 */
export function browserStore(): KeyValueStore | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}
