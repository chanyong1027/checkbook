import { useEffect, useState } from 'react'
import { getELibraries } from '../../api'
import { toUserMessage } from '../../api/errors.ts'
import type { ELibraryInfo } from '../../types'
import { MAX_ELIBRARIES } from '../../preferences/preferences.ts'
import { SearchIcon, SpinnerIcon } from '../shared/icons'
import { ChipRemoveButton } from './ChipRemoveButton'

/**
 * 전자도서관 고르기(설정 화면 안). 선택 값은 부모(설정 초안)가 가진다.
 * 옛 선택을 옮기는 중이면 5곳을 넘은 상태로 열릴 수 있다 — 그때는 줄이기만 하게 하고 저장은 부모가 막는다.
 */
export function ELibraryPicker({
  selectedIds,
  onChange,
}: {
  selectedIds: number[]
  onChange: (ids: number[]) => void
}) {
  const [libraries, setLibraries] = useState<ELibraryInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    getELibraries()
      .then(setLibraries)
      .catch(e => setError(toUserMessage(e, '전자도서관 목록을 불러오지 못했습니다.')))
      .finally(() => setLoading(false))
  }, [])

  const selected = new Set(selectedIds)
  const overLimit = selectedIds.length > MAX_ELIBRARIES

  function toggle(id: number) {
    if (selected.has(id)) onChange(selectedIds.filter(v => v !== id))
    else if (selectedIds.length < MAX_ELIBRARIES) onChange([...selectedIds, id])
  }

  if (loading) {
    return (
      <div className="flex justify-center py-8">
        <SpinnerIcon size={24} strokeWidth={2.2} className="text-ink-400" />
      </div>
    )
  }
  if (error) {
    return <div role="alert" className="p-3 bg-status-danger-soft rounded-2xl text-status-danger text-meta">{error}</div>
  }

  const filtered = [...(keyword
    ? libraries.filter(l => l.name.includes(keyword) || l.region?.includes(keyword) || l.vendorType?.includes(keyword))
    : libraries
  )].sort((a, b) => a.name.localeCompare(b.name, 'ko'))
  // 목록에 없는 ID(사라진 기관)도 칩으로 보여야 지울 수 있다
  const byId = new Map(libraries.map(l => [l.libraryId, l]))

  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <p className="text-meta font-semibold text-ink-700">
          선택
          <span className={`ml-1.5 ${overLimit ? 'text-status-danger' : 'text-ink-500'}`}>
            {selectedIds.length}/{MAX_ELIBRARIES}
          </span>
        </p>
        {selectedIds.length > 0 && (
          <button type="button" onClick={() => onChange([])} className="min-h-10 px-2 -mr-2 text-label font-semibold text-primary hover:underline cursor-pointer">
            전체 해제
          </button>
        )}
      </div>

      {selectedIds.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mb-3">
          {selectedIds.map(id => {
            const name = byId.get(id)?.name ?? '찾을 수 없는 도서관'
            return (
              <span key={id} className="inline-flex items-center gap-1 pl-3 pr-1 py-1 rounded-full bg-accent-soft border border-accent/30 text-label text-accent-strong font-semibold">
                {name}
                <ChipRemoveButton label={`${name} 선택 해제`} onClick={() => toggle(id)} />
              </span>
            )
          })}
        </div>
      )}

      {overLimit && (
        <p role="alert" className="text-label text-status-danger mb-2">
          전자도서관은 이제 {MAX_ELIBRARIES}곳까지 고를 수 있어요. {selectedIds.length - MAX_ELIBRARIES}곳을 빼 주세요
        </p>
      )}

      <div className="relative mb-2">
        <SearchIcon size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-500" />
        <input
          type="text"
          placeholder="이름, 지역으로 필터"
          aria-label="전자도서관 이름·지역 필터"
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          className="w-full pl-9 pr-4 py-2.5 rounded-xl border border-ink-200 bg-white text-body
            focus:outline-none focus:ring-4 focus:ring-accent/30 focus:border-primary transition placeholder:text-ink-500"
        />
      </div>

      <div className="max-h-56 overflow-y-auto scrollbar-thin space-y-0.5 bg-white rounded-2xl border border-ink-100 p-1">
        {filtered.length === 0 && <p className="text-meta text-ink-600 text-center py-4">맞는 도서관이 없어요</p>}
        {filtered.map(lib => {
          const checked = selected.has(lib.libraryId)
          const disabled = !checked && selectedIds.length >= MAX_ELIBRARIES
          return (
            <label
              key={lib.libraryId}
              className={`flex items-center gap-3 px-3 min-h-11 rounded-xl transition
                ${disabled ? 'opacity-40 cursor-not-allowed' : 'hover:bg-ink-50 cursor-pointer'}`}
            >
              <input
                type="checkbox"
                checked={checked}
                disabled={disabled}
                onChange={() => toggle(lib.libraryId)}
                className="w-4 h-4 accent-primary shrink-0"
              />
              <span className="text-body text-ink-900 flex-1 truncate">{lib.name}</span>
              <span className="text-label text-ink-500 shrink-0">{lib.region}</span>
            </label>
          )
        })}
      </div>
    </div>
  )
}
