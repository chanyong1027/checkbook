import assert from 'node:assert/strict'
import test from 'node:test'

import { ApiError, toApiError, toUserMessage } from './errors.ts'

test('toApiError extracts message and code from JSON error body', async () => {
  const error = await toApiError(
    new Response(
      JSON.stringify({
        code: 'INVALID_SEARCH_KEYWORD',
        message: '검색어를 입력해주세요.',
      }),
      {
        status: 400,
        headers: {
          'Content-Type': 'application/json',
        },
      },
    ),
  )

  assert.ok(error instanceof ApiError)
  assert.equal(error.status, 400)
  assert.equal(error.code, 'INVALID_SEARCH_KEYWORD')
  assert.equal(error.message, '검색어를 입력해주세요.')
})

test('toApiError falls back to generic message for non-JSON body', async () => {
  const error = await toApiError(
    new Response('Bad Gateway', {
      status: 502,
      headers: {
        'Content-Type': 'text/plain',
      },
    }),
  )

  assert.equal(error.status, 502)
  assert.equal(error.code, undefined)
  assert.equal(error.message, '요청 처리 중 오류가 발생했습니다.')
})

test('toUserMessage returns API message for ApiError', () => {
  const message = toUserMessage(new ApiError('검색어를 입력해주세요.', 400, 'INVALID_SEARCH_KEYWORD'), '검색 실패')

  assert.equal(message, '검색어를 입력해주세요.')
})

test('toUserMessage maps fetch failures to network message', () => {
  const message = toUserMessage(new Error('Failed to fetch'), '검색 실패')

  assert.equal(message, '네트워크 연결을 확인해주세요.')
})

test('toUserMessage falls back for unknown errors', () => {
  const message = toUserMessage(new Error('unexpected'), '검색 실패')

  assert.equal(message, '검색 실패')
})
