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
// 관측 전용(게이트 금지, 11-08 추가): YES24 초당 선제한(100ms당 1건) 때문에 식별을 보내지 않은 비율 — 10rps 근처에서 커진다
const newBookRateLimited = new Rate('new_book_rate_limited');
// 관측 전용: 책 정보가 YES24가 아닌 곳에서 온 비율(metadata.identification.source != YES24).
// k6는 힌트를 보내므로(아래 HINT) YES24를 건너뛰거나 실패하면 대체는 HINT가 되고, 밀리 호출은 그대로 나간다
const identificationFallback = new Rate('identification_fallback');
// 힌트: 실제 화면처럼 후보 검색(카카오)에서 이미 가진 정보를 같이 보낸다(11-08 측정 준비 결정).
// 힌트가 없으면 YES24를 건너뛴 요청(10rps 근처 선제한)은 식별이 NONE이 되어 밀리를 부르지 않는다 — 부하가 실제보다 가벼워진다.
// 값은 합성이다. 밀리 스텁은 검색어와 무관하게 같은 응답을 주고 밀리 결과 캐시가 없어 고정값으로 충분하다.
// 서버 계약(BookIdentificationService.validHint): 제목 필수, 글자 200자·표지 1,000자 이하, 표지는 http(s)만 쓴다
const HINT = {
  title: '부하 측정용 합성 도서',
  author: '합성 저자',
  publisher: '합성 출판사',
  cover: 'https://example.invalid/loadtest-cover.jpg',
};
const HINT_QUERY = Object.keys(HINT).map((key) => `${key}=${encodeURIComponent(HINT[key])}`).join('&');
if (!__ENV.RUN || !/^\d{1,2}$/.test(__ENV.RUN)) { throw new Error('RUN 필수(1~2자리 숫자)'); }
const RUN = String(Number(__ENV.RUN)).padStart(2, '0');
// 무작위 도착 근사(11-09, 방법론 리뷰 1차 지적 2): constant-arrival-rate는 반복을 같은 간격으로 시작해 몰림이 없다.
// THIN이 있으면 RATE는 '시도율'이고 반복마다 확률 THIN으로만 요청을 보낸다(베르누이 솎아내기). 시도율이 높고 THIN이 작을수록
// 도착이 포아송에 가까워진다(예: 15rps = RATE 150 × THIN 0.1, 시도 간격 6.7ms). 실제 도착 수는 이항분포라 rps가 흔들린다
// (2분 15rps면 1800 ± 약 40건, 1σ) — 균일 도착용 ±2% 게이트 대신 ±3σ로 본다. THIN이 없으면 지금처럼 균일 도착
const THIN = __ENV.THIN === undefined || __ENV.THIN === '' ? 1 : Number(__ENV.THIN);
if (!(THIN > 0 && THIN <= 1)) { throw new Error('THIN은 0 초과 1 이하'); }
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
function sectionDetail(statuses, section) {
  return statuses.find((s) => s && s.section === section) || null;
}
function sectionStatus(statuses, section) {
  const found = sectionDetail(statuses, section);
  return found ? found.status : null;
}
function addFailedIfAttempted(rate, status) {
  if (status !== null && status !== 'SKIPPED') {
    rate.add(status === 'FAILED');
  }
}
export default function () {
  if (THIN < 1 && Math.random() >= THIN) {
    return; // 이번 시도는 요청하지 않는다(무작위 도착 근사). ISBN은 반복 번호로 만들어 건너뛰어도 겹치지 않는다
  }
  const res = http.get(`http://localhost:8080/api/search?q=${uniqueIsbn13()}&lat=37.5665&lon=126.9780&${HINT_QUERY}`);
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

  // availability 보조 — NEW_BOOK==FAILED = YES24 식별 실패(타임아웃·오류·429, 그리고 서킷이 열려 보내지 않은 경우).
  // 찾지 못함(NOT_FOUND)은 SUCCESS, 우리 쪽 선제한·하루 한도로 안 보낸 경우는 SKIPPED라 여기 잡히지 않는다
  const newBook = sectionDetail(statuses, 'NEW_BOOK');
  identificationFailed.add(newBook !== null && newBook.status === 'FAILED');
  // 분모 = 모든 HTTP 200(위 지표와 같음). SKIPPED+RATE_LIMITED만 센다 — YES24 429(보냈다가 거절)는 FAILED+RATE_LIMITED라 위에 잡힌다
  newBookRateLimited.add(newBook !== null && newBook.status === 'SKIPPED' && newBook.reason === 'RATE_LIMITED');
  const identification = body.metadata && body.metadata.identification;
  identificationFallback.add(!identification || identification.source !== 'YES24');
}
