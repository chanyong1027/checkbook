import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 섹션별 성패는 http 레벨에 안 드러나므로 커스텀 메트릭으로 뽑는다
const publicLibraryFailed = new Rate('public_library_failed');
// 섹션 SUCCESS인데 도서관이 20곳 미만 = fan-out 내부(publicLibraryExecutor) 병목의 조용한 부분 실패
const publicLibraryIncomplete = new Rate('public_library_incomplete');

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
// 런 식별자를 섞어 런 간 ISBN 충돌 차단 — 유령 태스크가 남긴 스냅샷 캐시 적중 방지.
// 기본값 두면 충돌·재사용 사고가 나므로 필수화 (-e RUN=고유값)
if (!__ENV.RUN) {
  throw new Error('RUN 환경변수 필수: k6 run -e RUN=<런마다 고유한 2자리>');
}
const RUN = String(Number(__ENV.RUN) % 100).padStart(2, '0');
function uniqueIsbn13() {
  const tail = RUN + String(__VU).padStart(3, '0') + String(__ITER % 100000).padStart(5, '0');
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
    const libs = body.publicLibraries || [];
    publicLibraryIncomplete.add(!!(pub && pub.status === 'SUCCESS' && libs.length < 20));
  }

  sleep(0.5); // 사용자 think time 근사
}
