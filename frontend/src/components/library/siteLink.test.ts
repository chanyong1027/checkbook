import assert from 'node:assert/strict'
import { test } from 'node:test'

import type { PublicLibraryBatchItem, PublicLibrarySearchLink } from '../../types'
import { siteLinkOf } from './siteLink.ts'

const item = (searchLink: PublicLibrarySearchLink | null, homepage: string | null = 'https://lib.example/'): PublicLibraryBatchItem => ({
  libCode: 'A1', name: 'A도서관', address: null, latitude: 37.5, longitude: 127, distanceKm: 1, homepage,
  state: 'AVAILABLE', reason: null, searchLink,
})

test('GET 결과 링크는 "사이트에서 검색" 링크가 된다', () => {
  const link = siteLinkOf(item({ kind: 'RESULT', method: 'GET', url: 'https://lib.example/s?kwd=%ED%9D%B0', fields: null }))
  assert.deepEqual(link, { kind: 'get', href: 'https://lib.example/s?kwd=%ED%9D%B0', label: '사이트에서 검색' })
})

test('POST 결과 링크는 같은 문구의 버튼형 폼이 된다', () => {
  const link = siteLinkOf(item({ kind: 'RESULT', method: 'POST', url: 'https://lib.example/s', fields: { kwd: '흰' } }))
  assert.deepEqual(link, { kind: 'post', action: 'https://lib.example/s', fields: [['kwd', '흰']], label: '사이트에서 검색' })
})

test('검색 화면만 되는 곳은 "도서관 검색"', () => {
  const link = siteLinkOf(item({ kind: 'SEARCH_PAGE', method: 'GET', url: 'https://lib.example/search', fields: null }))
  assert.deepEqual(link, { kind: 'get', href: 'https://lib.example/search', label: '도서관 검색' })
})

test('링크가 없으면 지금처럼 홈페이지, 홈페이지도 없으면 없음', () => {
  assert.deepEqual(siteLinkOf(item(null)), { kind: 'get', href: 'https://lib.example/', label: '홈페이지' })
  assert.equal(siteLinkOf(item(null, null)), null)
})

test('http/https가 아닌 주소는 쓰지 않고 홈페이지로 돌아간다', () => {
  const link = siteLinkOf(item({ kind: 'RESULT', method: 'GET', url: 'javascript:alert(1)', fields: null }))
  assert.deepEqual(link, { kind: 'get', href: 'https://lib.example/', label: '홈페이지' })
})
