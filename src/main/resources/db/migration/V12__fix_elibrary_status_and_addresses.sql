-- 전자도서관 상태·주소 정정 (2026-11-28 자체 도메인 사이트 전수 확인)
-- 목록 행은 시드 SQL을 손으로 넣은 것이라 새 DB에는 이 행들이 없다 — 그때는 아무것도 바꾸지 않는다.
-- 각 UPDATE는 운영 DB의 지금 값(11-28 확인: id·주소·상태·robots_disallowed)과 같을 때만 바꾼다. 그 사이 손으로 고친 행은 건드리지 않는다.
-- 다시 여는 행은 모두 robots.txt가 검색 경로를 허용한다(11-28 확인). 지금 값(false)이 그 결과와 같아 robots 값은 그대로 둔다.

-- 1. 다시 검색되는 4곳을 같은 주소로 연다(운영 서버에서 두 번, 국내에서 한 번 검색됨)
UPDATE elibrary
SET status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE robots_disallowed = FALSE
  AND (id, base_url, status) IN (
      (532, 'http://lib.dangjin.go.kr:8080', 'INACTIVE'),
      (1740, 'http://ebook.nambu.ac.kr:8101', 'INACTIVE'),
      (1852, 'http://ebook.yewon.ac.kr:88', 'INACTIVE'),
      (1674, 'http://ebook.cku.ac.kr', 'DEAD')
  );

-- 2. 옛 주소가 닿지 않던 20곳(DEAD)이 새 주소에서 검색된다 — 주소를 바꾸고 연다.
-- 새 주소를 이미 다른 행이 쓰고 있으면 그 행은 건너뛴다(base_url은 유일해야 하고, 실패하면 배포가 멈춘다)
UPDATE elibrary SET base_url = 'http://ebook.gangnam.go.kr:9001', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 463 AND base_url = 'http://ebook.gangnam.go.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.gangnam.go.kr:9001');
UPDATE elibrary SET base_url = 'http://www.gb.go.kr:8085', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 480 AND base_url = 'http://www.gb.go.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://www.gb.go.kr:8085');
UPDATE elibrary SET base_url = 'http://eblib.seosan.go.kr:8070', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 563 AND base_url = 'http://eblib.seosan.go.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://eblib.seosan.go.kr:8070');
UPDATE elibrary SET base_url = 'http://nm.yeoncheon.go.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 601 AND base_url = 'http://nm.yeoncheon.go.kr:8091' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://nm.yeoncheon.go.kr');
UPDATE elibrary SET base_url = 'http://elibrary.ulsan.go.kr:8091', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 614 AND base_url = 'http://elibrary.ulsan.go.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://elibrary.ulsan.go.kr:8091');
UPDATE elibrary SET base_url = 'http://ebook.jemulpo.go.kr:8091', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 631 AND base_url = 'http://ebook.icdonggu.go.kr:8091' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.jemulpo.go.kr:8091');
UPDATE elibrary SET base_url = 'http://ebook-incheonlib.mlss.co.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 632 AND base_url = 'http://ebook.michuhollib.go.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook-incheonlib.mlss.co.kr');
UPDATE elibrary SET base_url = 'http://ebook-kidslib.mlss.co.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 648 AND base_url = 'http://ebook.mlss.co.kr:8100' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook-kidslib.mlss.co.kr');
UPDATE elibrary SET base_url = 'http://e-lib.ice.go.kr:8097', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 681 AND base_url = 'http://ebook.ihl.kr:8097' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://e-lib.ice.go.kr:8097');
UPDATE elibrary SET base_url = 'http://ebook.u1.ac.kr:9090', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1660 AND base_url = 'http://ebook.u1.ac.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.u1.ac.kr:9090');
UPDATE elibrary SET base_url = 'http://kyobo-seoul.konkuk.ac.kr:8083', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1686 AND base_url = 'http://kyobo-seoul.konkuk.ac.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://kyobo-seoul.konkuk.ac.kr:8083');
UPDATE elibrary SET base_url = 'http://ebook.kyonggi.ac.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1692 AND base_url = 'http://ebook.kyonggi.ac.kr:8001' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.kyonggi.ac.kr');
UPDATE elibrary SET base_url = 'http://ebook-afa.mlss.co.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1713 AND base_url = 'http://ebook.aa.af.mil' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook-afa.mlss.co.kr');
UPDATE elibrary SET base_url = 'http://ebook-kbtus.mlss.co.kr', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1755 AND base_url = 'http://ebook.kbtus.ac.kr:8090' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook-kbtus.mlss.co.kr');
UPDATE elibrary SET base_url = 'http://ebook1.dongguk.edu:8081', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1759 AND base_url = 'http://ebook1.dongguk.edu' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook1.dongguk.edu:8081');
UPDATE elibrary SET base_url = 'http://ebook.shinhan.ac.kr:8090', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1839 AND base_url = 'http://ebook.shinhan.ac.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.shinhan.ac.kr:8090');
UPDATE elibrary SET base_url = 'http://dyebook.mokpo.ac.kr:88', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1873 AND base_url = 'http://ebook.dorip.ac.kr:88' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://dyebook.mokpo.ac.kr:88');
UPDATE elibrary SET base_url = 'http://elib.ck.ac.kr:8091', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1890 AND base_url = 'http://elib.ck.ac.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://elib.ck.ac.kr:8091');
UPDATE elibrary SET base_url = 'http://ebook.cpu.ac.kr:8091', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1901 AND base_url = 'http://ebook.cbpc.ac.kr:8091' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.cpu.ac.kr:8091');
UPDATE elibrary SET base_url = 'http://elib.knuh.ac.kr:8082', status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
WHERE id = 1920 AND base_url = 'http://elib.knuh.ac.kr' AND status = 'DEAD' AND robots_disallowed = FALSE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://elib.knuh.ac.kr:8082');

-- 3. 노출 중인 2곳의 검색 포트가 바뀌었다. 옛 포트는 robots.txt가 막았고 새 포트는 허용한다
UPDATE elibrary SET base_url = 'http://scnulib.scnu.ac.kr:88', robots_disallowed = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id = 1727 AND base_url = 'http://scnulib.scnu.ac.kr:8000' AND status = 'ACTIVE' AND robots_disallowed = TRUE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://scnulib.scnu.ac.kr:88');
UPDATE elibrary SET base_url = 'http://ebook.hansei.ac.kr:8087', robots_disallowed = FALSE, updated_at = CURRENT_TIMESTAMP
WHERE id = 1931 AND base_url = 'http://ebook.hansei.ac.kr:8089' AND status = 'ACTIVE' AND robots_disallowed = TRUE
  AND NOT EXISTS (SELECT 1 FROM elibrary other WHERE other.base_url = 'http://ebook.hansei.ac.kr:8087');

-- 4. 노출 중이지만 검색이 실패하는 15곳을 숨긴다(INACTIVE — 사이트가 다시 되면 robots를 다시 확인한 뒤 status를 되돌린다)
-- 운영 서버에서 두 번 닿지 않고 국내에서는 두 번 검색된다(8곳)
-- 인증서 만료·인증서 체인 오류·https 응답 없음으로 국내에서도 검색이 실패한다(607 · 664 · 1784)
-- 국내에서 서버 오류가 두 번(1774 503 · 1781 500), 검색 응답 시간 초과(1854, 실제 검색 1번 — 나머지는 연결 지연),
-- 모든 검색 경로 404가 운영 서버·국내 두 번씩(1975)
UPDATE elibrary
SET status = 'INACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'ACTIVE'
  AND (id, base_url) IN (
      (496, 'http://ebk.gwangsan.go.kr'),
      (515, 'http://ebook.gunsan.go.kr:8001'),
      (672, 'http://ebook.ptlib.go.kr'),
      (1673, 'http://ebook.gachon.ac.kr:8080'),
      (1682, 'http://ebook.kangwon.ac.kr'),
      (1729, 'http://ebook.gc.ac.kr:88/elibrary-front'),
      (1794, 'http://kyoboelib.smuc.ac.kr:8091'),
      (1885, 'http://julisebook.joongbu.ac.kr:8089'),
      (607, 'http://www.ysplib.go.kr:9444'),
      (664, 'http://e-lib.cbe.go.kr:8092'),
      (1784, 'http://ebook.cup.ac.kr:88'),
      (1774, 'http://ebook.mokwon.ac.kr:8091'),
      (1781, 'http://ebook.baewha.ac.kr:8091'),
      (1854, 'http://ebook.wsu.ac.kr'),
      (1975, 'http://www.textbook.ac')
  );
