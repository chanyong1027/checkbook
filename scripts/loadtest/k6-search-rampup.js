import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 섹션별 성패는 http 레벨에 안 드러나므로 커스텀 메트릭으로 뽑는다
const publicLibraryFailed = new Rate('public_library_failed');

export const options = {
  scenarios: {
    rampup: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '60s', target: 1 },   // baseline: 동시성 1 (설계 가정과 일치)
        { duration: '60s', target: 5 },
        { duration: '60s', target: 10 },
        { duration: '60s', target: 20 },
      ],
    },
  },
  thresholds: {
    // 실패 기준이 아니라 리포트 구간 표시용
    http_req_duration: ['p(95)<10000'],
  },
};

// 요청마다 유일한 13자리 ISBN → 스냅샷 캐시 우회, 매 요청 fan-out 20건 실행
function uniqueIsbn13() {
  const tail = String(__VU).padStart(4, '0') + String(__ITER % 1000000).padStart(6, '0');
  return '978' + tail;
}

export default function () {
  const isbn = uniqueIsbn13();
  const res = http.get(
    `http://localhost:8080/api/search?q=${isbn}&lat=37.5665&lon=126.9780`,
    { tags: { name: 'search' } },
  );

  check(res, { 'status 200': (r) => r.status === 200 });

  if (res.status === 200) {
    const body = res.json();
    const statuses = (body.metadata && body.metadata.sectionStatuses) || [];
    const pub = statuses.find((s) => s.section === 'PUBLIC_LIBRARY');
    publicLibraryFailed.add(!!(pub && pub.status === 'FAILED'));
  }

  sleep(0.5); // 사용자 think time 근사
}
