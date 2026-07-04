import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
const publicLibraryFailed = new Rate('public_library_failed');
const publicLibraryIncomplete = new Rate('public_library_incomplete');
if (!__ENV.RUN || !/^\d{1,2}$/.test(__ENV.RUN)) { throw new Error('RUN 필수(1~2자리 숫자)'); }
const RUN = String(Number(__ENV.RUN)).padStart(2, '0');
export const options = {
  scenarios: {
    openloop: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 15), timeUnit: '1s', duration: '2m',
      preAllocatedVUs: 80, maxVUs: 120,
    },
  },
};
function uniqueIsbn13() {
  const tail = RUN + String(__VU % 1000).padStart(3, '0') + String(__ITER % 100000).padStart(5, '0');
  return '978' + tail;
}
export default function () {
  const res = http.get(`http://localhost:8080/api/search?q=${uniqueIsbn13()}&lat=37.5665&lon=126.9780`);
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) {
    const body = res.json();
    const statuses = (body.metadata && body.metadata.sectionStatuses) || [];
    const pub = statuses.find((s) => s.section === 'PUBLIC_LIBRARY');
    publicLibraryFailed.add(!!(pub && pub.status === 'FAILED'));
    const libs = body.publicLibraries || [];
    publicLibraryIncomplete.add(!!(pub && pub.status === 'SUCCESS' && libs.length < 20));
  }
}
