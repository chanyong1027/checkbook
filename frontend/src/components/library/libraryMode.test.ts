import assert from 'node:assert/strict'
import { test } from 'node:test'

import type { KeyValueStore } from '../../preferences/preferences.ts'
import { initialMode, readLastMode, shouldAutoLocate, writeLastMode } from './libraryMode.ts'

function memoryStore(): KeyValueStore & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: k => data.get(k) ?? null,
    setItem: (k, v) => { data.set(k, v) },
    removeItem: k => { data.delete(k) },
  }
}

test('마지막에 주변을 봤으면 저장한 도서관이 있어도 주변으로 연다', () => {
  assert.equal(initialMode(3, 'nearby'), 'nearby')
})

test('기억이 없거나 내 도서관이면 저장한 곳이 있을 때 내 도서관, 없으면 주변', () => {
  assert.equal(initialMode(2, null), 'saved')
  assert.equal(initialMode(2, 'saved'), 'saved')
  assert.equal(initialMode(0, 'saved'), 'nearby')
  assert.equal(initialMode(0, null), 'nearby')
})

test('기억한 모드를 읽고 쓴다. 모르는 값·저장소 없음·저장소 오류는 기억 없음으로', () => {
  const store = memoryStore()
  writeLastMode(store, 'nearby')
  assert.equal(readLastMode(store), 'nearby')
  store.data.set('checkbook.library.mode', 'map')
  assert.equal(readLastMode(store), null)
  assert.equal(readLastMode(null), null)
  const broken: KeyValueStore = {
    getItem: () => { throw new Error('denied') },
    setItem: () => { throw new Error('denied') },
    removeItem: () => {},
  }
  assert.equal(readLastMode(broken), null)
  assert.doesNotThrow(() => writeLastMode(broken, 'saved'))
})

test('위치 권한이 이미 허용이고 주변 모드에 기준점이 없을 때만 자동으로 위치를 받는다', () => {
  const base = { permission: 'granted' as const, mode: 'nearby' as const, hasOrigin: false, loading: false }
  assert.equal(shouldAutoLocate(base), true)
  // 물어봐야 하거나 거부면 팝업을 먼저 띄우지 않는다 — 버튼으로
  assert.equal(shouldAutoLocate({ ...base, permission: 'prompt' }), false)
  assert.equal(shouldAutoLocate({ ...base, permission: 'denied' }), false)
  assert.equal(shouldAutoLocate({ ...base, permission: null }), false)
  assert.equal(shouldAutoLocate({ ...base, mode: 'saved' }), false)
  assert.equal(shouldAutoLocate({ ...base, hasOrigin: true }), false)
  assert.equal(shouldAutoLocate({ ...base, loading: true }), false)
})
