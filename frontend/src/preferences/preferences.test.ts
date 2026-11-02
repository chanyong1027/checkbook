import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  EMPTY_PREFERENCES,
  LEGACY_ELIB_KEY,
  PREFERENCES_KEY,
  loadPreferences,
  normalizePreferences,
  savePreferences,
  type KeyValueStore,
} from './preferences.ts'

function memoryStore(initial: Record<string, string> = {}, { failWrites = false } = {}) {
  const data = new Map(Object.entries(initial))
  const store: KeyValueStore = {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => {
      if (failWrites) throw new Error('QuotaExceededError')
      data.set(key, value)
    },
    removeItem: key => {
      data.delete(key)
    },
  }
  return { store, data }
}

const lib = (libCode: string) => ({ libCode, name: `${libCode} 도서관` })

test('정상 저장값을 읽는다', () => {
  const saved = { version: 1, publicLibraries: [lib('A')], eLibraryIds: [3], referenceLibrary: lib('R') }
  const { store } = memoryStore({ [PREFERENCES_KEY]: JSON.stringify(saved) })

  const loaded = loadPreferences(store)

  assert.deepEqual(loaded.preferences, saved)
  assert.equal(loaded.pendingELibraryIds, null)
})

test('정리: 중복·잘못된 값을 빼고 5곳까지만 남긴다', () => {
  const normalized = normalizePreferences({
    version: 1,
    publicLibraries: [lib('A'), lib('A'), { libCode: '', name: 'x' }, lib('B'), lib('C'), lib('D'), lib('E'), lib('F')],
    eLibraryIds: [1, 1, -2, 2.5, '3', 4, 5, 6, 7, 8],
    referenceLibrary: { libCode: 'R' },
  })

  assert.deepEqual(normalized?.publicLibraries.map(l => l.libCode), ['A', 'B', 'C', 'D', 'E'])
  assert.deepEqual(normalized?.eLibraryIds, [1, 4, 5, 6, 7])
  assert.equal(normalized?.referenceLibrary, null)
})

test('손상되거나 모르는 버전이면 기본값으로 시작하고 원문은 지우지 않는다', () => {
  for (const raw of ['{깨진 JSON', JSON.stringify({ version: 2, eLibraryIds: [1] }), '"문자열"']) {
    const { store, data } = memoryStore({ [PREFERENCES_KEY]: raw })

    const loaded = loadPreferences(store)

    assert.deepEqual(loaded.preferences, EMPTY_PREFERENCES)
    assert.equal(data.get(PREFERENCES_KEY), raw)
  }
})

test('옛 전자도서관 선택이 5곳 이하면 새 형식으로 옮기고 옛 키를 지운다', () => {
  const { store, data } = memoryStore({ [LEGACY_ELIB_KEY]: JSON.stringify([7, 3, 7]) })

  const loaded = loadPreferences(store)

  assert.deepEqual(loaded.preferences.eLibraryIds, [7, 3])
  assert.equal(loaded.pendingELibraryIds, null)
  assert.deepEqual(JSON.parse(data.get(PREFERENCES_KEY) ?? 'null').eLibraryIds, [7, 3])
  assert.equal(data.has(LEGACY_ELIB_KEY), false)
})

test('옛 선택이 5곳을 넘으면 자르지 않고 고를 때까지 남겨 둔다', () => {
  const legacy = JSON.stringify([1, 2, 3, 4, 5, 6, 7])
  const { store, data } = memoryStore({ [LEGACY_ELIB_KEY]: legacy })

  const loaded = loadPreferences(store)

  assert.deepEqual(loaded.preferences.eLibraryIds, [])
  assert.deepEqual(loaded.pendingELibraryIds, [1, 2, 3, 4, 5, 6, 7])
  assert.equal(data.get(LEGACY_ELIB_KEY), legacy)
  assert.equal(data.has(PREFERENCES_KEY), false)
})

test('이관 저장이 실패하면 옛 키를 지우지 않는다', () => {
  const legacy = JSON.stringify([1, 2])
  const { store, data } = memoryStore({ [LEGACY_ELIB_KEY]: legacy }, { failWrites: true })

  const loaded = loadPreferences(store)

  assert.deepEqual(loaded.preferences.eLibraryIds, [1, 2])
  assert.equal(data.get(LEGACY_ELIB_KEY), legacy)
})

test('저장 실패와 저장소 차단은 false를 돌려준다', () => {
  assert.equal(savePreferences(memoryStore({}, { failWrites: true }).store, EMPTY_PREFERENCES), false)
  assert.equal(savePreferences(null, EMPTY_PREFERENCES), false)
  assert.deepEqual(loadPreferences(null).preferences, EMPTY_PREFERENCES)
})

test('읽기만 해도 던지는 저장소는 기본값', () => {
  const throwing: KeyValueStore = {
    getItem: () => { throw new Error('SecurityError') },
    setItem: () => {},
    removeItem: () => {},
  }

  assert.deepEqual(loadPreferences(throwing).preferences, EMPTY_PREFERENCES)
})
