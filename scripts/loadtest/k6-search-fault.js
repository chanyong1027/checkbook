import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const publicLibraryFailed = new Rate('public_library_failed');
// 섹션 SUCCESS인데 요청 page(5곳) 미만 = fan-out 내부(publicLibraryExecutor) 병목의 조용한 부분 실패
const publicLibraryIncomplete = new Rate('public_library_incomplete');

// 4분 지속 부하: t=60s에 지연 주입, t=180s에 해제 (외부 스크립트로 수동 조작)
export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: '4m',
    },
  },
};

// 런 식별자를 섞어 런 간 ISBN 충돌 차단 — 유령 태스크가 남긴 스냅샷 캐시 적중 방지.
// 기본값 두면 충돌·재사용 사고가 나므로 필수화 (-e RUN=고유값)
if (!__ENV.RUN || !/^\d{1,2}$/.test(__ENV.RUN)) {
  // 비수치 RUN이면 ISBN이 '978NaN...'이 되어 KEYWORD 경로로 빠지고
  // 전 섹션 SKIPPED의 무부하 측정이 정상 런처럼 통과한다 — 숫자만 허용
  throw new Error('RUN 환경변수 필수(1~2자리 숫자): k6 run -e RUN=<런마다 고유값>');
}
const RUN = String(Number(__ENV.RUN)).padStart(2, '0');
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
    const body = res.json();
    const statuses = (body.metadata || {}).sectionStatuses || [];
    const pub = statuses.find((s) => s.section === 'PUBLIC_LIBRARY');
    publicLibraryFailed.add(!!(pub && pub.status === 'FAILED'));
    const libs = body.publicLibraries || [];
    publicLibraryIncomplete.add(!!(pub && pub.status === 'SUCCESS' && libs.length < 5));
  }
  sleep(0.5);
}
