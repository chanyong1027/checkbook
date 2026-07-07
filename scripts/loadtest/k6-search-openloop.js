import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
// SLI 정의·분모·시그니처: notes/decisions/2026-07-07-sli-slo-체계-정의.md §6
const publicLibraryFailed = new Rate('public_library_failed');
const publicLibraryIncomplete = new Rate('public_library_incomplete');
const usedBookFailed = new Rate('used_book_failed');
const subscriptionFailed = new Rate('subscription_failed');
const identificationFailed = new Rate('identification_failed');
const searchAvailable = new Rate('search_available');
if (!__ENV.RUN || !/^\d{1,2}$/.test(__ENV.RUN)) { throw new Error('RUN 필수(1~2자리 숫자)'); }
const RUN = String(Number(__ENV.RUN)).padStart(2, '0');
export const options = {
  scenarios: {
    openloop: {
      executor: 'constant-arrival-rate',
      // rate는 정수만 허용(k6 int64) — 소수 도착률은 TIMEUNIT을 늘려 표현: rate=11,TIMEUNIT=2s → 5.5/s
      rate: Number(__ENV.RATE || 15), timeUnit: __ENV.TIMEUNIT || '1s', duration: '2m',
      preAllocatedVUs: 80, maxVUs: 120,
    },
  },
  // 상정 SLO 게이트 — identification_failed 등 신규 지표는 첫 실측 전까지 관측 전용(게이트 금지)
  thresholds: {
    'http_req_duration': ['p(95)<3000'],
    'public_library_failed': ['rate<0.05'],
    'http_req_failed': ['rate<0.01'],
  },
};
function uniqueIsbn13() {
  const tail = RUN + String(__VU % 1000).padStart(3, '0') + String(__ITER % 100000).padStart(5, '0');
  return '978' + tail;
}
function sectionStatus(statuses, section) {
  const found = statuses.find((s) => s && s.section === section);
  return found ? found.status : null;
}
function addFailedIfAttempted(rate, status) {
  if (status !== null && status !== 'SKIPPED') {
    rate.add(status === 'FAILED');
  }
}
export default function () {
  const res = http.get(`http://localhost:8080/api/search?q=${uniqueIsbn13()}&lat=37.5665&lon=126.9780`);
  check(res, { 'status 200': (r) => r.status === 200 });

  let body = null;
  let statuses = null;
  if (res.status === 200) {
    try {
      body = res.json();
      const raw = body.metadata && body.metadata.sectionStatuses;
      statuses = Array.isArray(raw) ? raw : []; // 비배열/부재는 [] — 기존 지표 분모(모든 200) 보존
    } catch (e) {
      statuses = null; // 200인데 JSON 파싱 실패 — 불가용으로 계상
      console.warn(`응답 계약 붕괴: status=${res.status} JSON 파싱 실패`); // 조용한 all-green 방지
    }
  }
  const usedStatus = statuses ? sectionStatus(statuses, 'USED_BOOK') : null;

  // availability — 분모는 전체 요청: 5xx·전송 실패·파싱 실패도 add(false)가 찍혀야 sanity 역할이 성립.
  // usedStatus === null(sectionStatuses 부재·USED_BOOK 누락)도 계약 위반이므로 불가용
  searchAvailable.add(res.status === 200 && statuses !== null && usedStatus !== null && usedStatus !== 'SKIPPED');

  if (statuses === null) {
    return;
  }

  // quality 기존 2종 — 분모=모든 HTTP 200 (§4~5 결과와의 비교 가능성 때문에 시맨틱 변경 금지)
  const pubStatus = sectionStatus(statuses, 'PUBLIC_LIBRARY');
  publicLibraryFailed.add(pubStatus === 'FAILED');
  const libs = body.publicLibraries || [];
  publicLibraryIncomplete.add(pubStatus === 'SUCCESS' && libs.length < 20);

  // quality 신규 2종 — SKIPPED(미시도)는 분모에서 제외: availability 문제의 quality 이중 계상 방지
  addFailedIfAttempted(usedBookFailed, usedStatus);
  addFailedIfAttempted(subscriptionFailed, sectionStatus(statuses, 'SUBSCRIPTION'));

  // availability 보조 — NEW_BOOK==FAILED가 식별 실패 시그니처(키워드 스킵·ISBN 우회 양쪽 커버)
  identificationFailed.add(sectionStatus(statuses, 'NEW_BOOK') === 'FAILED');
}
