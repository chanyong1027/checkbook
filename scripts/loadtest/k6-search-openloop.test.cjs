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

function runtime(status, body, extra = {}) {
  const metrics = new Map();
  const urls = [];
  const context = {
    Rate: class {
      constructor(name) { this.values = []; metrics.set(name, this.values); }
      add(value) { this.values.push(value); }
    },
    http: { get: (url) => { urls.push(url); return { status, json: () => {
      if (body instanceof Error) throw body;
      return body;
    } }; } },
    check: () => {}, console: { warn: () => {} },
    __ENV: { RUN: '71' }, __VU: 23, __ITER: 0,
    // k6/execution 대역: 테스트 전체에서 하나씩 늘어나는 반복 번호
    exec: { scenario: { iterationInTest: 0 } },
    ...extra,
  };
  runInNewContext(source, context);
  return { context, metrics, urls };
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

test('every search sends a hint the server accepts, like the real screen', () => {
  // 힌트가 없으면 YES24를 건너뛴 요청은 식별이 NONE이 되어 밀리를 부르지 않는다(11-08 측정 준비 결정)
  const { context, urls } = runtime(200, { metadata: { sectionStatuses: sections }, publicLibraries: [] });
  context.iteration();
  assert.equal(urls.length, 1);
  const params = new URL(urls[0]).searchParams;
  assert.match(params.get('q'), /^97[89]\d{10}$/);
  assert.equal(params.get('lat'), '37.5665');
  assert.equal(params.get('lon'), '126.9780');
  // 서버 계약(BookIdentificationService.validHint): 제목 필수, 글자 200자·표지 1,000자 이하, 표지는 http(s)
  const title = params.get('title');
  assert.ok(title && title.trim().length > 0 && title.length <= 200);
  for (const key of ['author', 'publisher']) {
    assert.ok(params.get(key) && params.get(key).length <= 200, key);
  }
  assert.match(params.get('cover'), /^https?:\/\//);
  assert.ok(params.get('cover').length <= 1000);
  // 한글 제목이 인코딩을 거쳐 그대로 되돌아온다(깨진 힌트는 서버가 무효로 버린다)
  assert.equal(title, '부하 측정용 합성 도서');
});

test('random-arrival mode sends a request only with probability THIN, and uniform mode always sends', () => {
  const body = { metadata: { sectionStatuses: sections }, publicLibraries: [] };
  // THIN 없음 = 균일 도착: 난수와 상관없이 매 반복 요청
  const uniform = runtime(200, body, { Math: Object.assign(Object.create(Math), { random: () => 0.99 }) });
  uniform.context.iteration();
  assert.equal(uniform.urls.length, 1);
  // THIN=0.1: 난수가 0.1 미만일 때만 요청, 건너뛴 반복은 어떤 지표에도 표본을 남기지 않는다
  let next = 0.5;
  const thinned = runtime(200, body, {
    __ENV: { RUN: '71', THIN: '0.1' },
    Math: Object.assign(Object.create(Math), { random: () => next }),
  });
  thinned.context.iteration();
  assert.equal(thinned.urls.length, 0);
  assert.equal(thinned.metrics.get('search_available').length, 0);
  next = 0.05;
  thinned.context.iteration();
  assert.equal(thinned.urls.length, 1);
  assert.equal(thinned.metrics.get('search_available').length, 1);
});

test('an invalid THIN stops the run instead of silently sending nothing', () => {
  for (const bad of ['0', '-0.1', '1.5', 'abc']) {
    assert.throws(() => runtime(200, {}, { __ENV: { RUN: '71', THIN: bad } }), /THIN/, bad);
  }
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

test('our own YES24 per-second skip is counted separately from a YES24 429', () => {
  const cases = [
    // [NEW_BOOK 상태, 사유, 식별 출처, 기대 new_book_rate_limited, identification_failed, identification_fallback]
    ['SKIPPED', 'RATE_LIMITED', 'NONE', true, false, true],
    ['FAILED', 'RATE_LIMITED', 'NONE', false, true, true],
    ['SKIPPED', 'DAILY_LIMIT', 'NONE', false, false, true],
    ['SUCCESS', null, 'YES24', false, false, false],
    // 힌트를 보내면(지금 k6) 선제한으로 건너뛰어도 대체는 HINT이고 밀리는 시도된다
    ['SKIPPED', 'RATE_LIMITED', 'HINT', true, false, true],
  ];
  for (const [status, reason, source, limited, failed, fallback] of cases) {
    const statuses = [
      { section: 'PUBLIC_LIBRARY', status: 'SUCCESS', reason: null },
      { section: 'NEW_BOOK', status, reason },
      { section: 'SUBSCRIPTION', status: source !== 'NONE' ? 'SUCCESS' : 'SKIPPED', reason: source !== 'NONE' ? null : 'NO_METADATA' },
    ];
    const { context, metrics } = runtime(200, {
      metadata: { sectionStatuses: statuses, identification: { source, yes24: 'RATE_LIMITED' } }, publicLibraries: [],
    });
    context.iteration();
    const label = `${status}/${reason}/${source}`;
    assert.deepEqual(metrics.get('new_book_rate_limited'), [limited], label);
    assert.deepEqual(metrics.get('identification_failed'), [failed], label);
    assert.deepEqual(metrics.get('identification_fallback'), [fallback], label);
    // 밀리를 건너뛴 응답은 구독 실패율 분모에서 빠진다
    assert.deepEqual(metrics.get('subscription_failed'), source !== 'NONE' ? [false] : [], label);
  }
});

test('HTTP failures and unparseable success responses count against availability', () => {
  for (const [status, body] of [[500, {}], [200, new Error('bad JSON')]]) {
    const { context, metrics } = runtime(status, body);
    context.iteration();
    assert.deepEqual(metrics.get('search_available'), [false]);
  }
});
