import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';
// SLI 정의·분모·시그니처: notes/decisions/2026-07-07-sli-slo-체계-정의.md §6
const publicLibraryFailed = new Rate('public_library_failed');
const publicLibraryIncomplete = new Rate('public_library_incomplete');
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
// 요청마다 처음 보는 ISBN(캐시 적중 방지). 978 + RUN(2) + 시나리오 전체 반복 번호(7) + 체크 숫자.
// VU별 반복 번호(__ITER)를 쓰면 체크 숫자 때문에 자리가 모자라 긴 실행에서 반복된다 — 시나리오 전체 번호는 1천만 개까지 고유.
// iterationInTest는 시나리오마다 0부터 다시 센다: 시나리오를 추가하면 접두를 나눌 것
function uniqueIsbn13() {
  const base = '978' + RUN + String(exec.scenario.iterationInTest % 10000000).padStart(7, '0');
  const sum = [...base].reduce((total, digit, index) => total + Number(digit) * (index % 2 === 0 ? 1 : 3), 0);
  return base + String((10 - sum % 10) % 10);
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
  // PR-2: USED_BOOK은 외부 호출 없는 링크라 가용성 판단에서 뺀다. 가용 = HTTP 200 + 남은 세 섹션 존재
  // + 도서관 섹션이 건너뛰지 않음(SLI §2 '비스킵'). k6는 항상 위치를 보내므로 PUBLIC_LIBRARY==SKIPPED는
  // 비표준 ISBN(생성기 고장)이나 위치 누락 = 하네스 오류다. 이 조건이 없으면 고장 난 하네스가 빠르고 FAILED 없는
  // 응답만 받아 모든 지표가 초록으로 통과한다(옛 usedStatus!=='SKIPPED' 조건이 하던 역할)
  const contractValid = statuses !== null && ['PUBLIC_LIBRARY', 'NEW_BOOK', 'SUBSCRIPTION']
    .every(section => sectionStatus(statuses, section) !== null);
  const libraryAttempted = statuses !== null && sectionStatus(statuses, 'PUBLIC_LIBRARY') !== 'SKIPPED';
  searchAvailable.add(res.status === 200 && contractValid && libraryAttempted);

  if (statuses === null) {
    return;
  }

  // quality 기존 2종 — 분모=모든 HTTP 200 (§4~5 결과와의 비교 가능성 때문에 시맨틱 변경 금지)
  const pubStatus = sectionStatus(statuses, 'PUBLIC_LIBRARY');
  publicLibraryFailed.add(pubStatus === 'FAILED');
  const libs = body.publicLibraries || [];
  publicLibraryIncomplete.add(pubStatus === 'SUCCESS' && libs.length < 5);

  // subscription quality — SKIPPED(미시도)는 분모에서 제외. 중고 외부 조회 실패율은 PR-2에서 폐기.
  addFailedIfAttempted(subscriptionFailed, sectionStatus(statuses, 'SUBSCRIPTION'));

  // availability 보조 — NEW_BOOK==FAILED = YES24 식별 호출이 나갔다가 실패(타임아웃·오류·429).
  // 찾지 못함(NOT_FOUND)은 SUCCESS, 우리 쪽 선제한으로 안 보낸 경우는 SKIPPED라 여기 잡히지 않는다
  identificationFailed.add(sectionStatus(statuses, 'NEW_BOOK') === 'FAILED');
}
