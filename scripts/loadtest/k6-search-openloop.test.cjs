const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');

const source = readFileSync(join(__dirname, 'k6-search-openloop.js'), 'utf8')
  .replace(/^import .*;\r?$/gm, '')
  .replace('export const options', 'const options')
  .replace('export default function ()', 'function iteration()')
  + '\nglobalThis.iteration = iteration; globalThis.makeIsbn = uniqueIsbn13;';

function runtime(status, body) {
  const metrics = new Map();
  const context = {
    Rate: class {
      constructor(name) { this.values = []; metrics.set(name, this.values); }
      add(value) { this.values.push(value); }
    },
    http: { get: () => ({ status, json: () => {
      if (body instanceof Error) throw body;
      return body;
    } }) },
    check: () => {}, console: { warn: () => {} },
    __ENV: { RUN: '71' }, __VU: 23, __ITER: 0,
    // k6/execution 대역: 테스트 전체에서 하나씩 늘어나는 반복 번호
    exec: { scenario: { iterationInTest: 0 } },
  };
  runInNewContext(source, context);
  return { context, metrics };
}

const sections = ['PUBLIC_LIBRARY', 'NEW_BOOK', 'SUBSCRIPTION'].map(section => ({ section, status: 'SUCCESS' }));

test('generated ISBNs pass the same prefix/checksum rule as the application', () => {
  const { context } = runtime(200, {});
  const seen = new Set();
  for (let index = 0; index < 1000; index++) {
    context.exec.scenario.iterationInTest = index;
    const isbn = context.makeIsbn();
    assert.match(isbn, /^97[89]\d{10}$/);
    const sum = [...isbn.slice(0, 12)].reduce((total, digit, offset) => total + Number(digit) * (offset % 2 === 0 ? 1 : 3), 0);
    assert.equal(Number(isbn[12]), (10 - sum % 10) % 10);
    seen.add(isbn);
  }
  assert.equal(seen.size, 1000);
});

test('ISBN depends on the test-wide iteration, so a long run never repeats per VU', () => {
  const { context } = runtime(200, {});
  // 같은 VU의 같은 __ITER라도 전체 반복 번호가 다르면 다른 ISBN (VU당 반복 번호 4자리 한계 제거)
  context.__ITER = 0;
  context.exec.scenario.iterationInTest = 0;
  const first = context.makeIsbn();
  context.exec.scenario.iterationInTest = 10000;
  assert.notEqual(context.makeIsbn(), first);
  // 다른 VU라도 VU 번호에 의존하지 않는다
  context.__VU = 99;
  context.exec.scenario.iterationInTest = 0;
  assert.equal(context.makeIsbn(), first);
  // 978 + RUN(71) + 전체 반복 번호 7자리 + 체크 숫자
  context.exec.scenario.iterationInTest = 9999999;
  assert.match(context.makeIsbn(), /^978719999999\d$/);
  assert.match(first, /^978710000000\d$/);
});

test('availability does not depend on the removed used-price lookup', () => {
  const { context, metrics } = runtime(200, { metadata: { sectionStatuses: sections }, publicLibraries: [] });
  context.iteration();
  assert.deepEqual(metrics.get('search_available'), [true]);
  assert.equal(metrics.has('used_book_failed'), false);
});

test('a broken harness (non-standard ISBN or missing location) is not counted as available', () => {
  // 생성기가 고장 나 비표준 코드가 가면 앱은 도서관·YES24를 건너뛰어 빨라지고 FAILED도 없다 — 가용으로 세면 거짓 용량 증가
  for (const reason of ['NON_STANDARD_ISBN', 'NO_LOCATION']) {
    const skipped = [
      { section: 'PUBLIC_LIBRARY', status: 'SKIPPED', reason },
      { section: 'USED_BOOK', status: 'SKIPPED', reason },
      { section: 'NEW_BOOK', status: 'SKIPPED', reason },
      { section: 'SUBSCRIPTION', status: 'SKIPPED', reason: 'NO_METADATA' },
    ];
    const { context, metrics } = runtime(200, { metadata: { sectionStatuses: skipped }, publicLibraries: [] });
    context.iteration();
    assert.deepEqual(metrics.get('search_available'), [false], reason);
  }
});

test('a missing remaining section violates availability contract', () => {
  const { context, metrics } = runtime(200, { metadata: { sectionStatuses: sections.slice(1) } });
  context.iteration();
  assert.deepEqual(metrics.get('search_available'), [false]);
});

test('HTTP failures and unparseable success responses count against availability', () => {
  for (const [status, body] of [[500, {}], [200, new Error('bad JSON')]]) {
    const { context, metrics } = runtime(status, body);
    context.iteration();
    assert.deepEqual(metrics.get('search_available'), [false]);
  }
});
