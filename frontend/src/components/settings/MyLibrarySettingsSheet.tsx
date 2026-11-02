import { useEffect, useState } from 'react'
import { BottomSheet } from '../shared/BottomSheet'
import { ConfirmDialog } from '../shared/ConfirmDialog'
import { MAX_ELIBRARIES, type Preferences } from '../../preferences/preferences.ts'
import { ELibraryPicker } from './ELibraryPicker'
import { PublicLibraryPicker } from './PublicLibraryPicker'

export type SettingsSection = 'public' | 'elibrary'

function sameJson(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

/**
 * 내 도서관 설정. 헤더와 상세 화면이 같은 화면을 연다.
 * 편집은 초안에서 하고 저장을 눌러야 적용된다(취소하면 조회 대상이 바뀌지 않음). 바꾼 게 있으면 닫을 때 확인한다.
 */
export function MyLibrarySettingsSheet({
  open,
  section,
  preferences,
  pendingELibraryIds,
  onSave,
  onClose,
}: {
  open: boolean
  section: SettingsSection
  preferences: Preferences
  pendingELibraryIds: number[] | null
  onSave: (next: Preferences) => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState<Preferences>(preferences)
  const [confirmOpen, setConfirmOpen] = useState(false)

  // 열 때마다 지금 값으로 초안을 새로 만든다. 옛 선택을 옮기는 중이면 그 목록에서 시작해 줄이게 한다
  useEffect(() => {
    if (!open) return
    setDraft(pendingELibraryIds ? { ...preferences, eLibraryIds: pendingELibraryIds } : preferences)
    setConfirmOpen(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 원하는 칸으로 스크롤(상세 화면의 '전자도서관 고르기'에서 열 때)
  useEffect(() => {
    // 옛 선택을 줄여야 저장되는 상태면 어디서 열었든 전자도서관 칸을 먼저 보여 준다
    if (!open || (section !== 'elibrary' && !pendingELibraryIds)) return
    const timer = setTimeout(() => document.getElementById('settings-elibrary')?.scrollIntoView({ block: 'start' }), 0)
    return () => clearTimeout(timer)
  }, [open, section, pendingELibraryIds])

  const baseline = pendingELibraryIds ? { ...preferences, eLibraryIds: pendingELibraryIds } : preferences
  const dirty = !sameJson(draft, baseline)
  const eLibraryOverLimit = draft.eLibraryIds.length > MAX_ELIBRARIES

  function requestClose() {
    if (dirty) setConfirmOpen(true)
    else onClose()
  }

  return (
    <>
      <BottomSheet open={open} onClose={requestClose} title="내 도서관 설정">
        <section aria-labelledby="settings-public-title" className="mb-6">
          <h4 id="settings-public-title" className="text-body font-bold text-ink-900 mb-2">공공도서관</h4>
          <PublicLibraryPicker
            libraries={draft.publicLibraries}
            reference={draft.referenceLibrary}
            onLibrariesChange={publicLibraries => setDraft(d => ({ ...d, publicLibraries }))}
            onReferenceChange={referenceLibrary => setDraft(d => ({ ...d, referenceLibrary }))}
          />
        </section>

        <section id="settings-elibrary" aria-labelledby="settings-elibrary-title" className="mb-4">
          <h4 id="settings-elibrary-title" className="text-body font-bold text-ink-900 mb-1">전자도서관</h4>
          <p className="text-label text-ink-600 mb-2">가입·이용 자격은 각 도서관에서 확인해 주세요</p>
          <ELibraryPicker
            selectedIds={draft.eLibraryIds}
            onChange={eLibraryIds => setDraft(d => ({ ...d, eLibraryIds }))}
          />
        </section>

        <p className="text-caption text-ink-600 mb-3">이 브라우저에만 저장돼요. 다른 기기와는 동기화되지 않아요.</p>

        {eLibraryOverLimit && (
          <p className="text-label text-status-danger mb-2">전자도서관을 {MAX_ELIBRARIES}곳 이하로 줄여야 저장할 수 있어요</p>
        )}
        {/* 스크롤 중에도 버튼이 보이게. 스크롤 중엔 시트 본문 여백이 안 먹어 홈 인디케이터만큼 띄운다(끝까지 내리면 여백이 조금 겹침) */}
        <div className="flex gap-2 sticky bottom-0 bg-white pt-2" style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}>
          <button
            type="button"
            onClick={requestClose}
            className="flex-1 min-h-12 border border-ink-200 rounded-xl text-meta font-semibold text-ink-900 hover:border-ink-400 transition cursor-pointer bg-white"
          >
            취소
          </button>
          <button
            type="button"
            onClick={() => onSave(draft)}
            disabled={eLibraryOverLimit}
            className="flex-1 min-h-12 bg-primary text-white rounded-xl font-bold text-meta hover:bg-primary-dark transition
              disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
          >
            저장
          </button>
        </div>
      </BottomSheet>

      <ConfirmDialog
        open={confirmOpen}
        title="변경사항을 버리시겠어요?"
        message="저장하지 않으면 고른 도서관 변경 내용이 사라집니다."
        confirmLabel="버리고 닫기"
        cancelLabel="계속 편집"
        destructive
        onConfirm={() => {
          setConfirmOpen(false)
          onClose()
        }}
        onCancel={() => setConfirmOpen(false)}
      />
    </>
  )
}
