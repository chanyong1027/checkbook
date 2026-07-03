import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const publicLibraryFailed = new Rate('public_library_failed');

// 4분 지속 부하: t=60s에 지연 주입, t=180s에 해제 (외부 스크립트로 수동 조작)
export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: 5,
      duration: '4m',
    },
  },
};

function uniqueIsbn13() {
  const tail = String(__VU).padStart(4, '0') + String(__ITER % 1000000).padStart(6, '0');
  return '978' + tail;
}

export default function () {
  const res = http.get(
    `http://localhost:8080/api/search?q=${uniqueIsbn13()}&lat=37.5665&lon=126.9780`,
  );
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) {
    const statuses = (res.json().metadata || {}).sectionStatuses || [];
    const pub = statuses.find((s) => s.section === 'PUBLIC_LIBRARY');
    publicLibraryFailed.add(!!(pub && pub.status === 'FAILED'));
  }
  sleep(0.5);
}
