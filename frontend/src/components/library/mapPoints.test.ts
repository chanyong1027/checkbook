import assert from 'node:assert/strict'
import { test } from 'node:test'

import type { LibraryState, PublicLibraryBatchItem } from '../../types'
import { toMapPoints } from './mapPoints.ts'

const item = (code: string, state: LibraryState, latitude = 37.5, longitude = 127.0): PublicLibraryBatchItem => ({
  libCode: code, name: `${code} 도서관`, address: null, latitude, longitude, distanceKm: 1, homepage: null, state, reason: null,
})

test('목록 순서대로 번호를 붙이고 소장 여부를 색·글자로 나눈다(대출 상태는 마커에서 가르지 않는다)', () => {
  const points = toMapPoints([item('A', 'AVAILABLE'), item('B', 'ON_LOAN'), item('C', 'NOT_HELD'), item('D', 'UNKNOWN')])

  assert.deepEqual(points.map(p => [p.code, p.label, p.tone, p.stateLabel]), [
    ['A', '1', 'ok', '소장'],
    ['B', '2', 'ok', '소장'],
    ['C', '3', 'off', '미소장'],
    ['D', '4', 'unknown', '확인 못 함'],
  ])
})

test('좌표가 이상한 기관은 지도에서만 빼고 번호는 목록 기준을 지킨다', () => {
  const points = toMapPoints([item('A', 'AVAILABLE', 0, 0), item('B', 'ON_LOAN', Number.NaN, 127), item('C', 'AVAILABLE')])

  assert.deepEqual(points.map(p => [p.code, p.label]), [['C', '3']])
})
