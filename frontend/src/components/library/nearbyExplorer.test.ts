import assert from 'node:assert/strict'
import { test } from 'node:test'

import type { LibraryState, PublicLibraryBatchItem, PublicLibraryBatchResponse } from '../../types'
import {
  INITIAL_STATE,
  NEW_ROUND,
  applyBatch,
  hasTimeForNextBatch,
  mergeRetry,
  type ExplorerState,
  type RoundState,
} from './nearbyExplorer.ts'

function item(code: string, state: LibraryState, distanceKm: number | null = Number(code.slice(1)) / 10): PublicLibraryBatchItem {
  return {
    libCode: code, name: code, address: null, latitude: 37.5, longitude: 127.0,
    distanceKm, homepage: null, state, reason: state === 'UNKNOWN' ? 'TIMEOUT' : null,
  }
}

function batch(items: PublicLibraryBatchItem[], nextOffset: number | null, stopReason = 'NONE'): PublicLibraryBatchResponse {
  return { items, nextOffset, stopReason }
}

/** 배치 여러 개를 차례로 반영(실제 훅처럼 멈춤 신호가 오면 그만) */
function run(responses: Array<[number, PublicLibraryBatchResponse]>, start: ExplorerState = INITIAL_STATE) {
  let state = start
  let round: RoundState = NEW_ROUND
  let stop = null
  for (const [offset, res] of responses) {
    const result = applyBatch(state, round, offset, res)
    state = result.state
    round = result.round
    stop = result.stop
    if (stop) break
  }
  return { state, round, stop }
}

test('첫 5곳에 소장 2곳이면 멈추지 않고 다음 구간을 이어 찾는다', () => {
  const { state, stop } = run([[0, batch([
    item('L1', 'AVAILABLE'), item('L2', 'NOT_HELD'), item('L3', 'ON_LOAN'), item('L4', 'NOT_HELD'), item('L5', 'NOT_HELD'),
  ], 5)]])

  assert.equal(stop, null)
  assert.deepEqual(state.found.map(f => f.libCode), ['L1', 'L3'])
  assert.equal(state.nextOffset, 5)
  assert.equal(state.seen.length, 5)
})

test('정확히 5곳을 채우면 목표 달성으로 멈추고 진행 위치는 구간 끝이다', () => {
  const held = ['L1', 'L2', 'L3', 'L4', 'L5'].map(c => item(c, 'ON_LOAN'))
  const { state, stop } = run([[0, batch(held, 5)]])

  assert.equal(stop, 'GOAL')
  assert.equal(state.found.length, 5)
  assert.equal(state.nextOffset, 5)
})

test('목표를 넘친 소장 기관은 버리고 다음 탐색은 버린 첫 기관 위치부터', () => {
  // 첫 배치에서 4곳, 둘째 배치(offset 5)에서 3곳 → 1곳만 담고 L7(offset 6) 위치부터 다시
  const { state, stop } = run([
    [0, batch(['L1', 'L2', 'L3', 'L4'].map(c => item(c, 'AVAILABLE')).concat(item('L5', 'NOT_HELD')), 5)],
    [5, batch([item('L6', 'AVAILABLE'), item('L7', 'AVAILABLE'), item('L8', 'NOT_HELD'), item('L9', 'ON_LOAN')], 10)],
  ])

  assert.equal(stop, 'GOAL')
  assert.deepEqual(state.found.map(f => f.libCode), ['L1', 'L2', 'L3', 'L4', 'L6'])
  assert.equal(state.nextOffset, 6)
  assert.equal(state.seen.includes('L7'), false)

  // 더 찾기: L7부터 다시 와도 중복 없이 이어 간다
  const more = run([[6, batch([item('L7', 'AVAILABLE'), item('L8', 'NOT_HELD'), item('L9', 'ON_LOAN')], null)]], state)
  assert.deepEqual(more.state.found.map(f => f.libCode), ['L1', 'L2', 'L3', 'L4', 'L6', 'L7', 'L9'])
  assert.equal(more.stop, 'EXHAUSTED')
})

test('후보 20곳을 보면 소장이 모자라도 이번 탐색을 멈춘다', () => {
  const none = (from: number) => [0, 1, 2, 3, 4].map(i => item(`L${from + i}`, 'NOT_HELD'))
  const { round, stop, state } = run([
    [0, batch(none(0), 5)], [5, batch(none(5), 10)], [10, batch(none(10), 15)], [15, batch(none(15), 20)],
  ])

  assert.equal(stop, 'ROUND_LIMIT')
  assert.equal(round.checked, 20)
  assert.equal(state.nextOffset, 20)
  assert.equal(state.found.length, 0)
})

test('후보 소진이면 EXHAUSTED', () => {
  const { stop, state } = run([[0, batch([item('L1', 'NOT_HELD')], null)]])
  assert.equal(stop, 'EXHAUSTED')
  assert.equal(state.nextOffset, null)
})

test('서버 중단 사유가 오면 그대로 멈추고 진행 위치는 지킨다', () => {
  const { stop, state } = run([[0, batch([item('L1', 'UNKNOWN'), item('L2', 'NOT_HELD')], 5, 'DAILY_LIMIT')]])
  assert.equal(stop, 'DAILY_LIMIT')
  assert.equal(state.nextOffset, 5)
  assert.deepEqual(state.unknown.map(u => u.libCode), ['L1'])
})

test('배치가 전부 확인 못 함이면 멈춘다(실패를 미소장으로 치지 않음)', () => {
  const { stop, state } = run([[0, batch([item('L1', 'UNKNOWN'), item('L2', 'UNKNOWN')], 5)]])
  assert.equal(stop, 'ALL_UNKNOWN')
  assert.equal(state.found.length, 0)
  assert.equal(state.unknown.length, 2)
})

test('일부만 확인 못 함이면 계속 찾는다', () => {
  const { stop } = run([[0, batch([item('L1', 'UNKNOWN'), item('L2', 'NOT_HELD')], 5)]])
  assert.equal(stop, null)
})

test('다시 확인: 복구된 소장 기관은 원래 거리로 합치고, 미소장은 빼고, 진행 위치는 그대로', () => {
  const start: ExplorerState = {
    found: [item('L2', 'AVAILABLE')],
    unknown: [item('L1', 'UNKNOWN'), item('L3', 'UNKNOWN'), item('L4', 'UNKNOWN')],
    seen: ['L1', 'L2', 'L3', 'L4'],
    nextOffset: 10,
  }
  // SAVED 응답은 거리가 없다
  const merged = mergeRetry(start, batch([
    { ...item('L1', 'ON_LOAN'), distanceKm: null },
    { ...item('L3', 'NOT_HELD'), distanceKm: null },
    { ...item('L4', 'UNKNOWN'), distanceKm: null, reason: 'CIRCUIT_OPEN' },
  ], null))

  assert.deepEqual(merged.found.map(f => [f.libCode, f.distanceKm]), [['L1', 0.1], ['L2', 0.2]])
  assert.deepEqual(merged.unknown.map(u => [u.libCode, u.reason]), [['L4', 'CIRCUIT_OPEN']])
  assert.equal(merged.nextOffset, 10)
})

test('다음 배치는 남은 시간이 2.4초 이상일 때만', () => {
  assert.equal(hasTimeForNextBatch(7_600), true)
  assert.equal(hasTimeForNextBatch(7_700), false)
})
