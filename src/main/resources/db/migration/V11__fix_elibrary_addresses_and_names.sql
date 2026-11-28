-- 전자도서관 목록 정정 (2026-11-28 재조사, notes/research/2026-11-28-전자도서관-해외차단-재조사.md)
-- 목록 행은 시드 SQL을 손으로 넣은 것이라 새 DB에는 이 행들이 없다 — 그때는 아무것도 바꾸지 않는다.
-- 각 UPDATE는 운영 DB의 지금 값(11-28 확인)과 같을 때만 바꾼다. 그 사이 손으로 고친 행은 건드리지 않는다.

-- 1. 10-20에 해외 IP 차단으로 숨겼으나 운영 서버(오사카)에서 직접 검색되는 6곳을 다시 연다.
-- (1758 덕성여자대학교(구독) https는 1757 http와 같은 사이트라 열지 않는다 — 같은 결과가 두 번 보이고 사이트 상한이 두 배가 된다)

-- 교보 호스팅 3곳: 주소는 그대로. robots.txt가 `Disallow: /`라 낮은 하루 상한(200)을 쓰게 한다
-- (10-21 robots 값 채우기는 ACTIVE만 대상이라 이 행들은 기본값 false로 남아 있었다)
UPDATE elibrary
SET status = 'ACTIVE',
    robots_disallowed = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'INACTIVE'
  AND (id, base_url) IN (
      (249, 'https://rsfc.dkyobobook.co.kr'),
      (1010, 'https://ebook-yonchon.dkyobobook.co.kr'),
      (1505, 'https://yongam.dkyobobook.co.kr')
  );

-- 대학 3곳: 검색 경로가 /elibrary-front로 옮겨 갔다(robots.txt 없음 = 허용). 평택대는 포트와 https도 바뀌었다
UPDATE elibrary
SET status = 'ACTIVE',
    base_url = 'https://libebook.tu.ac.kr:446/elibrary-front',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1761 AND base_url = 'https://libebook.tu.ac.kr:446' AND status = 'INACTIVE';

UPDATE elibrary
SET status = 'ACTIVE',
    base_url = 'https://library.jbnu.ac.kr:444/elibrary-front',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1875 AND base_url = 'https://library.jbnu.ac.kr:444' AND status = 'INACTIVE';

UPDATE elibrary
SET status = 'ACTIVE',
    base_url = 'https://ebook.ptu.ac.kr:446/elibrary-front',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 1906 AND base_url = 'http://ebook.ptu.ac.kr:8091' AND status = 'INACTIVE';

-- 2. 광주광역시·전라남도가 전남광주통합특별시로 바뀐 이름(사용자가 알려 준 6곳). 상태와 주소는 그대로다
-- (502·638은 이름만 바뀌고 계속 숨겨 둔다 — 서울 중계 대상)
UPDATE elibrary SET name = '전남광주통합특별시 서구 통합도서관(구독)', updated_at = CURRENT_TIMESTAMP
WHERE id = 500 AND name = '광주광역시 서구 통합전자도서관(구독)';

UPDATE elibrary SET name = '전남광주통합특별시교육청 광주통합도서관', updated_at = CURRENT_TIMESTAMP
WHERE id = 501 AND name = '광주광역시교육청 통합도서관';

UPDATE elibrary SET name = '전남광주통합특별시광주도서관', updated_at = CURRENT_TIMESTAMP
WHERE id = 502 AND name = '광주광역시립도서관';

UPDATE elibrary SET name = '전남광주통합특별시 북구 통합도서관', updated_at = CURRENT_TIMESTAMP
WHERE id = 503 AND name = '광주광역시북구통합도서관';

UPDATE elibrary SET name = '전남광주통합특별시전남도서관(구독)', updated_at = CURRENT_TIMESTAMP
WHERE id = 637 AND name = '전남도립도서관(구독)';

UPDATE elibrary SET name = '전남광주통합특별시교육청 전남도서관', updated_at = CURRENT_TIMESTAMP
WHERE id = 638 AND name = '전라남도교육청통합도서관';
