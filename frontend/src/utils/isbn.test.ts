import assert from 'node:assert/strict'
import { test } from 'node:test'

import { isValidIsbn13 } from './isbn.ts'

test('표준 ISBN13만 통과한다', () => {
  assert.equal(isValidIsbn13('9788936434120'), true)
  assert.equal(isValidIsbn13('9788936434121'), false) // 체크섬
  assert.equal(isValidIsbn13('2000000000008'), false) // 서점 내부 코드
  assert.equal(isValidIsbn13('978893643412'), false)
})
