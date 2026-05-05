import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'

import { searchBooks } from './index.ts'

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
