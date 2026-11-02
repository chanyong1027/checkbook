import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { MyLibrarySettingsSheet, type SettingsSection } from '../components/settings/MyLibrarySettingsSheet'
import {
  browserStore,
  loadPreferences,
  savePreferences,
  type Preferences,
} from './preferences.ts'

interface PreferencesValue {
  preferences: Preferences
  /** 옛 전자도서관 선택이 5곳을 넘어 아직 옮기지 못했으면 그 목록. 자동 검색을 멈추고 고르게 안내한다 */
  pendingELibraryIds: number[] | null
  openSettings: (section?: SettingsSection) => void
}

const PreferencesContext = createContext<PreferencesValue | null>(null)

const SAVE_NOTICE_MS = 5000

/** 설정의 기준은 React 상태다. localStorage는 처음 한 번 읽고, 저장할 때만 쓴다 */
export function PreferencesProvider({ children }: { children: React.ReactNode }) {
  const [initial] = useState(() => loadPreferences(browserStore()))
  const [preferences, setPreferences] = useState<Preferences>(initial.preferences)
  const [pendingELibraryIds, setPendingELibraryIds] = useState<number[] | null>(initial.pendingELibraryIds)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [section, setSection] = useState<SettingsSection>('public')
  const [notice, setNotice] = useState<string | null>(null)

  const openSettings = useCallback((target: SettingsSection = 'public') => {
    setSection(target)
    setSheetOpen(true)
  }, [])

  function handleSave(next: Preferences) {
    const persisted = savePreferences(browserStore(), next)
    // 저장에 실패해도 이번 이용에는 적용한다 — 다음 방문에 남지 않는다는 것만 알린다
    setPreferences(next)
    setPendingELibraryIds(null)
    setSheetOpen(false)
    setNotice(persisted ? null : '이번 이용에만 적용됐어요. 브라우저에 저장하지 못했어요.')
  }

  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setNotice(null), SAVE_NOTICE_MS)
    return () => clearTimeout(timer)
  }, [notice])

  const value = useMemo(
    () => ({ preferences, pendingELibraryIds, openSettings }),
    [preferences, pendingELibraryIds, openSettings],
  )

  return (
    <PreferencesContext.Provider value={value}>
      {children}
      <MyLibrarySettingsSheet
        open={sheetOpen}
        section={section}
        preferences={preferences}
        pendingELibraryIds={pendingELibraryIds}
        onSave={handleSave}
        onClose={() => setSheetOpen(false)}
      />
      <div role="status" aria-live="polite" className="fixed bottom-4 left-0 right-0 z-[60] flex justify-center px-4 pointer-events-none">
        {notice && (
          <p className="max-w-md px-4 py-3 rounded-xl bg-ink-900 text-white text-meta shadow-lg">{notice}</p>
        )}
      </div>
    </PreferencesContext.Provider>
  )
}

export function usePreferences(): PreferencesValue {
  const value = useContext(PreferencesContext)
  if (!value) throw new Error('usePreferences는 PreferencesProvider 안에서만 쓴다')
  return value
}
