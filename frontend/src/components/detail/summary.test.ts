import assert from 'node:assert/strict'
import test from 'node:test'

import type { ELibraryResult, ELibrarySearchResponse, LibraryState, PublicLibraryBatchItem } from '../../types'
import { summarizeELibraries, summarizeLibraries } from './summary.ts'

function lib(state: LibraryState): PublicLibraryBatchItem {
  return { libCode: String(Math.random()), name: '도서관', address: null, latitude: 0, longitude: 0, distanceKm: 1, homepage: null, state, reason: null }
}

function site(status: ELibraryResult['status'], books: { available: boolean }[] = []): ELibraryResult {
  return {
    libraryId: Math.random(),
    libraryName: '전자도서관',
    vendorType: 'X',
    status,
    elapsedMs: 0,
    books: books.map(b => ({ title: '책', author: '', available: b.available })) as ELibraryResult['books'],
  }
}

function elib(results: ELibraryResult[], failures = 0): ELibrarySearchResponse {
  return {
    results,
    metadata: {
      totalElapsedMs: 0,
      searchedAt: '',
      failures: Array.from({ length: failures }, (_, i) => ({ libraryId: i, libraryName: null, reason: '로그인 필요 도서관' })),
    },
  }
}

test('공공도서관: 확인 못 한 곳이 섞이면 없다고 단정하지 않는다', () => {
  assert.equal(summarizeLibraries([], 2, '가까운 곳엔 없음').value, '소장 없음 · 일부 확인 못 함')
  assert.equal(summarizeLibraries([], 0, '가까운 곳엔 없음').value, '가까운 곳엔 없음')
})

test('공공도서관: 대출 상태와 상관없이 소장한 곳 수를 말한다(대출 정보는 전날 기준이라 결론에 쓰지 않는다)', () => {
  const s = summarizeLibraries([lib('AVAILABLE'), lib('ON_LOAN'), lib('ON_LOAN')], 0, '-')
  assert.equal(s.value, '3곳 소장')
  assert.equal(s.tone, 'ok')
  assert.equal(summarizeLibraries([lib('ON_LOAN')], 0, '-').tone, 'ok')
})

test('공공도서관: 소장한 곳이 있고 확인 못 한 곳이 섞이면 그렇게 말한다', () => {
  assert.equal(summarizeLibraries([lib('ON_LOAN'), lib('AVAILABLE')], 3, '-').value, '2곳 소장 · 일부 확인 못 함')
})

test('전자도서관: 한 곳 대출중, 한 곳 시간 초과면 모두 대출중이라 하지 않는다', () => {
  const s = summarizeELibraries(elib([site('SUCCESS', [{ available: false }]), site('TIMEOUT')]))
  assert.equal(s.value, '1곳 대출중 · 일부 확인 못 함')
})

test('전자도서관: 성공한 곳이 빈 결과이고 실패가 섞이면 소장 없음으로 단정하지 않는다', () => {
  const s = summarizeELibraries(elib([site('SUCCESS'), site('FAILED')]))
  assert.equal(s.value, '소장 없음 · 일부 확인 못 함')
})

test('전자도서관: 검색 전에 빠진 곳(로그인 필요 등)도 확인 못 한 곳으로 센다', () => {
  const s = summarizeELibraries(elib([site('SUCCESS')], 1))
  assert.equal(s.value, '소장 없음 · 일부 확인 못 함')
})

test('전자도서관: 한 곳만 대출중이고 나머지가 소장 없음이면 모두 대출중이라 하지 않는다', () => {
  const s = summarizeELibraries(elib([site('SUCCESS', [{ available: false }]), site('SUCCESS'), site('SUCCESS')]))
  assert.equal(s.value, '1곳 대출중')
})

test('전자도서관: 모두 답했으면 기존 결론을 그대로 쓴다', () => {
  assert.equal(summarizeELibraries(elib([site('SUCCESS', [{ available: false }])])).value, '1곳 대출중')
  assert.equal(summarizeELibraries(elib([site('SUCCESS')])).value, '소장 없음')
  assert.deepEqual(summarizeELibraries(elib([site('SUCCESS', [{ available: true }]), site('TIMEOUT')])), { value: '1곳 대출가능', tone: 'ok' })
  assert.deepEqual(summarizeELibraries(elib([site('TIMEOUT')])), { value: '확인 못 함', tone: 'warn' })
})
