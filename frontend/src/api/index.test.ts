import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'

import { getNearbyLibraryBatch, getOffStores, searchBooks } from './index.ts'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
})

test('searchBooks throws backend message instead of raw JSON body', async () => {
  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        code: 'INVALID_SEARCH_KEYWORD',
        message: '검색어를 입력해주세요.',
        timestamp: '2026-04-27T14:35:38.561171792',
      }),
      {
        status: 400,
        headers: {
          'Content-Type': 'application/json',
        },
      },
    )) as typeof fetch

  await assert.rejects(
    () => searchBooks(''),
    (err: unknown) => {
      assert.ok(err instanceof Error)
      assert.equal(err.message, '검색어를 입력해주세요.')
      return true
    },
  )
})

test('주변 도서관·가까운 매장 요청은 좌표를 소수 셋째 자리로 줄여 보낸다', async () => {
  const urls: string[] = []
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    urls.push(String(input))
    return new Response(JSON.stringify({ items: [], nextOffset: null, stopReason: 'NONE' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as typeof fetch

  await getNearbyLibraryBatch('9788936434120', { lat: 37.566535, lon: 126.977969 }, 0)
  await getOffStores('9788936434120', 37.566535, 126.977969)

  for (const url of urls) {
    const params = new URL(url, 'http://localhost').searchParams
    assert.equal(params.get('lat'), '37.567')
    assert.equal(params.get('lon'), '126.978')
  }
})
