import assert from 'node:assert/strict'
import { test } from 'node:test'

import type { LibraryState, PublicLibraryBatchItem, PublicLibraryBatchResponse } from '../../types'
import { RoundDeadlineError, runExploreRound, type ExploreDeps } from './exploreLoop.ts'
import { INITIAL_STATE, type ExplorerState } from './nearbyExplorer.ts'

function item(code: string, state: LibraryState): PublicLibraryBatchItem {
  return {
    libCode: code, name: code, address: null, latitude: 37.5, longitude: 127.0,
    distanceKm: Number(code.slice(1)) / 10, homepage: null, state,
    reason: state === 'UNKNOWN' ? 'TIMEOUT' : null, searchLink: null,
  }
}

function batch(items: PublicLibraryBatchItem[], nextOffset: number | null, stopReason = 'NONE'): PublicLibraryBatchResponse {
  return { items, nextOffset, stopReason } as PublicLibraryBatchResponse
}

/** offset → 응답(또는 던질 오류). 부를 때마다 시계를 stepMs만큼 움직인다 */
function fakeDeps(
  responses: Record<number, PublicLibraryBatchResponse | Error>,
  opts: { stepMs?: number; startMs?: number; cancelAfterCall?: number } = {},
) {
  let clock = opts.startMs ?? 0
  const calls: Array<{ offset: number; remainingMs: number }> = []
  const shown: ExplorerState[] = []
  let cancelled = false
  const deps: ExploreDeps = {
    now: () => clock,
    isCancelled: () => cancelled,
    onBatch: s => { shown.push(s) },
    fetchBatch: async (offset, remainingMs) => {
      calls.push({ offset, remainingMs })
      clock += opts.stepMs ?? 500
      if (opts.cancelAfterCall === calls.length) cancelled = true
      const res = responses[offset]
      if (res === undefined) throw new Error(`no response for offset ${offset}`)
      if (res instanceof Error) throw res
      return res
    },
  }
  return { deps, calls, shown }
}

test('소장 5곳을 채울 때까지 배치를 이어 부르고, 배치마다 화면에 넘긴다', async () => {
  const { deps, calls, shown } = fakeDeps({
    0: batch([item('L1', 'AVAILABLE'), item('L2', 'NOT_HELD'), item('L3', 'ON_LOAN'), item('L4', 'NOT_HELD'), item('L5', 'NOT_HELD')], 5),
    5: batch([item('L6', 'AVAILABLE'), item('L7', 'AVAILABLE'), item('L8', 'AVAILABLE'), item('L9', 'NOT_HELD'), item('L10', 'NOT_HELD')], 10),
  })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled, false)
  if (result.cancelled) return
  assert.equal(result.stop, 'GOAL')
  assert.equal(result.error, null)
  assert.deepEqual(calls.map(c => c.offset), [0, 5])
  assert.equal(shown.length, 2)
  assert.deepEqual(result.state.found.map(f => f.libCode), ['L1', 'L3', 'L6', 'L7', 'L8'])
})

test('남은 거리가 없으면 부르지 않고 EXHAUSTED', async () => {
  const { deps, calls } = fakeDeps({})
  const result = await runExploreRound({ ...INITIAL_STATE, nextOffset: null }, deps)
  assert.deepEqual(result, { cancelled: false, state: { ...INITIAL_STATE, nextOffset: null }, stop: 'EXHAUSTED', error: null })
  assert.equal(calls.length, 0)
})

test('남은 시간을 배치에 넘기고, 다음 배치 시간이 모자라면 TIME으로 멈춘다', async () => {
  // 첫 배치가 7.7초 걸리면 남은 2.3초 < 2.4초라 두 번째를 보내지 않는다
  const { deps, calls } = fakeDeps({
    0: batch([item('L1', 'NOT_HELD'), item('L2', 'NOT_HELD'), item('L3', 'NOT_HELD'), item('L4', 'NOT_HELD'), item('L5', 'NOT_HELD')], 5),
  }, { stepMs: 7_700 })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled ? null : result.stop, 'TIME')
  assert.deepEqual(calls, [{ offset: 0, remainingMs: 10_000 }])
})

test('두 번째 배치에는 처음부터 잰 남은 시간을 넘긴다', async () => {
  const { deps, calls } = fakeDeps({
    0: batch([item('L1', 'NOT_HELD')], 5),
    5: batch([item('L6', 'NOT_HELD')], null),
  }, { stepMs: 1_000, startMs: 50_000 })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled ? null : result.stop, 'EXHAUSTED')
  assert.deepEqual(calls.map(c => c.remainingMs), [10_000, 9_000])
})

test('바깥 마감으로 끊기면 TIME, 받은 결과는 지킨다', async () => {
  const { deps } = fakeDeps({
    0: batch([item('L1', 'AVAILABLE'), item('L2', 'NOT_HELD')], 5),
    5: new RoundDeadlineError(),
  })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled, false)
  if (result.cancelled) return
  assert.equal(result.stop, 'TIME')
  assert.equal(result.error, null)
  assert.deepEqual(result.state.found.map(f => f.libCode), ['L1'])
  assert.equal(result.state.nextOffset, 5)
})

test('그 밖의 오류는 ERROR와 오류를 돌려주고, 진행 위치는 그대로 둔다', async () => {
  const boom = new Error('502')
  const { deps } = fakeDeps({ 0: batch([item('L1', 'NOT_HELD')], 5), 5: boom })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled, false)
  if (result.cancelled) return
  assert.equal(result.stop, 'ERROR')
  assert.equal(result.error, boom)
  assert.equal(result.state.nextOffset, 5)
})

test('응답을 기다리는 사이 취소되면 결과를 버리고 화면에 넘기지 않는다', async () => {
  const { deps, shown } = fakeDeps({ 0: batch([item('L1', 'AVAILABLE')], 5) }, { cancelAfterCall: 1 })
  assert.deepEqual(await runExploreRound(INITIAL_STATE, deps), { cancelled: true })
  assert.equal(shown.length, 0)
})

test('취소된 뒤의 오류는 ERROR가 아니라 취소', async () => {
  const { deps } = fakeDeps({ 0: new Error('network') }, { cancelAfterCall: 1 })
  assert.deepEqual(await runExploreRound(INITIAL_STATE, deps), { cancelled: true })
})

test('AbortError(마감이 아닌 중단)는 취소로 본다', async () => {
  const abort = Object.assign(new Error('aborted'), { name: 'AbortError' })
  const { deps } = fakeDeps({ 0: abort })
  assert.deepEqual(await runExploreRound(INITIAL_STATE, deps), { cancelled: true })
})

test('형태가 다른 응답(items 없음)은 밖으로 던지지 않고 ERROR', async () => {
  const { deps } = fakeDeps({ 0: { nextOffset: 5, stopReason: 'NONE' } as unknown as PublicLibraryBatchResponse })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled, false)
  if (result.cancelled) return
  assert.equal(result.stop, 'ERROR')
  assert.ok(result.error instanceof TypeError)
})

test('서버 중단 사유는 그대로 멈춤 사유가 된다', async () => {
  const { deps, calls } = fakeDeps({ 0: batch([item('L1', 'UNKNOWN')], 5, 'DAILY_LIMIT') })
  const result = await runExploreRound(INITIAL_STATE, deps)
  assert.equal(result.cancelled ? null : result.stop, 'DAILY_LIMIT')
  assert.equal(calls.length, 1)
})

test('취소된 뒤 마감 오류가 와도 취소가 먼저다', async () => {
  const { deps } = fakeDeps({ 0: new RoundDeadlineError() }, { cancelAfterCall: 1 })
  assert.deepEqual(await runExploreRound(INITIAL_STATE, deps), { cancelled: true })
})

test('더 찾기는 앞 결과를 지우지 않고 넘겨받은 위치부터 이어 찾는다', async () => {
  const from: ExplorerState = { found: [item('L1', 'AVAILABLE')], unknown: [], seen: ['L1', 'L2'], nextOffset: 5 }
  const { deps, calls } = fakeDeps({ 5: batch([item('L6', 'ON_LOAN')], null) })
  const result = await runExploreRound(from, deps)
  assert.deepEqual(calls.map(c => c.offset), [5])
  assert.equal(result.cancelled ? null : result.state.found.length, 2)
})
