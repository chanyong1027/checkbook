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

// 런 식별자(2자리)를 섞어 런 간 ISBN 충돌 차단 — 직전 런의 유령 태스크가 남긴
// 스냅샷 캐시에 적중해 측정이 낙관 왜곡되는 것을 방지 (k6 실행 시 -e RUN=NN 권장)
const RUN = String((Number(__ENV.RUN) || (Date.now() % 90) + 10) % 100).padStart(2, '0');
function uniqueIsbn13() {
  const tail = RUN + String(__VU).padStart(3, '0') + String(__ITER % 100000).padStart(5, '0');
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
