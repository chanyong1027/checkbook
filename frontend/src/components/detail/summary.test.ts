import assert from 'node:assert/strict'
import test from 'node:test'

import type { ELibraryResult, ELibrarySearchResponse, PublicLibraryInfo } from '../../types'
import { summarizeELibraries, summarizeLibraries, unansweredLibraryCount } from './summary.ts'

function lib(hasBook: boolean, loanAvailable = false): PublicLibraryInfo {
  return { libraryName: '도서관', hasBook, loanAvailable, address: '', latitude: 0, longitude: 0, distance: 1, homepage: '' }
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

test('응답 없던 도서관 수는 시도한 후보 수에서 받은 수를 뺀다', () => {
  assert.equal(unansweredLibraryCount(1, 3, null), 2)
  assert.equal(unansweredLibraryCount(2, 10, 3), 1)
  assert.equal(unansweredLibraryCount(3, null, null), 0)
})

test('공공도서관: 3곳 중 1곳만 미보유로 답하고 2곳이 실패하면 없다고 단정하지 않는다', () => {
  const s = summarizeLibraries([lib(false)], { total: 3, nextOffset: null, hasMore: false })
  assert.equal(s.value, '소장 없음 · 일부 확인 못 함')
})

test('공공도서관: 대출중인 곳이 있어도 실패가 섞이면 모두 대출중이라 하지 않는다', () => {
  const s = summarizeLibraries([lib(true)], { total: 3, nextOffset: null, hasMore: false })
  assert.equal(s.value, '1곳 대출중 · 일부 확인 못 함')
})

test('공공도서관: 실패가 없으면 기존 결론을 그대로 쓴다', () => {
  assert.equal(summarizeLibraries([lib(false)], { total: 1, nextOffset: null, hasMore: false }).value, '가까운 곳엔 없음')
  assert.equal(summarizeLibraries([lib(true)], { total: 1, nextOffset: null, hasMore: false }).value, '1곳 모두 대출중')
  assert.equal(summarizeLibraries([lib(true)], { total: 5, nextOffset: 1, hasMore: true }).value, '가까운 1곳 대출중')
})

test('공공도서관: 대출가능이 하나라도 있으면 실패가 섞여도 대출가능', () => {
  const s = summarizeLibraries([lib(true, true)], { total: 3, nextOffset: null, hasMore: false })
  assert.deepEqual(s, { value: '1곳 대출가능', tone: 'ok' })
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
