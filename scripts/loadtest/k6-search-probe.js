import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// 고정 VU 탐침 — 임계점·결론 수치는 rampup(스테이지 희석)이 아니라 이 스크립트로 잰다.
// 사용: k6 run -e VUS=<고정 VU 수> -e RUN=<런마다 고유한 1~2자리 숫자> [-e DURATION=90s] k6-search-probe.js
const publicLibraryFailed = new Rate('public_library_failed');
// 섹션 SUCCESS인데 요청 page(5곳) 미만 = fan-out 내부(publicLibraryExecutor) 병목의 조용한 부분 실패
const publicLibraryIncomplete = new Rate('public_library_incomplete');

if (!__ENV.RUN || !/^\d{1,2}$/.test(__ENV.RUN)) {
  // 비수치 RUN이면 ISBN이 '978NaN...'이 되어 KEYWORD 경로로 빠지고 무부하 측정이 조용히 통과,
  // 3자리 이상이면 % 100 절단으로 런 간 충돌이 재발한다 — 1~2자리 숫자만 허용
  throw new Error('RUN 환경변수 필수(1~2자리 숫자): k6 run -e VUS=8 -e RUN=42 ...');
}
if (!__ENV.VUS || !/^\d+$/.test(__ENV.VUS)) {
  throw new Error('VUS 환경변수 필수(숫자): k6 run -e VUS=8 -e RUN=42 ...');
}
const RUN = String(Number(__ENV.RUN)).padStart(2, '0');

export const options = {
  scenarios: {
    probe: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS),
      duration: __ENV.DURATION || '90s',
    },
  },
};

function uniqueIsbn13() {
  const tail = RUN + String(__VU).padStart(3, '0') + String(__ITER % 100000).padStart(5, '0');
  return '978' + tail;
}

export default function () {
  const res = http.get(
    `http://localhost:8080/api/search?q=${uniqueIsbn13()}&lat=37.5665&lon=126.9780`,
    { tags: { name: 'search' } },
  );

  check(res, { 'status 200': (r) => r.status === 200 });

  if (res.status === 200) {
    const body = res.json();
    const statuses = (body.metadata && body.metadata.sectionStatuses) || [];
    const pub = statuses.find((s) => s.section === 'PUBLIC_LIBRARY');
    publicLibraryFailed.add(!!(pub && pub.status === 'FAILED'));
    const libs = body.publicLibraries || [];
    publicLibraryIncomplete.add(!!(pub && pub.status === 'SUCCESS' && libs.length < 5));
  }

  sleep(0.5); // 사용자 think time 근사
}
