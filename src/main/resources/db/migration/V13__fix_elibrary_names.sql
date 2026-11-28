-- 전자도서관 이름 정정 (2026-11-28·29 사이트 확인)
-- 목록 행은 시드 SQL을 손으로 넣은 것이라 새 DB에는 이 행들이 없다 — 그때는 아무것도 바꾸지 않는다.
-- 각 UPDATE는 id와 운영 DB의 지금 이름(11-28 확인)이 같을 때만 바꾼다. 그 사이 손으로 고친 행은 건드리지 않는다. 상태와 주소는 그대로다.
-- 새 이름은 1·2절은 각 사이트가 스스로 적은 기관명을 따르고(꾸밈 기호와 '(재)'는 빼고, 기관명이 남으면 '전자도서관' 접미도 뺀다),
-- 3절은 사이트를 사람이 확인해 정했다.
-- V12 다음에 적용한다(V12가 다시 연 631·1755·1873·1901·1920·463도 여기서 이름을 고친다).

-- 1. 기관 이름이 바뀌었거나 저장된 이름이 틀린 곳(통합·개칭·학교명)
UPDATE elibrary SET name = '전남광주통합특별시 서구청', updated_at = CURRENT_TIMESTAMP WHERE id = 286 AND name = '광주광역시 서구청';
UPDATE elibrary SET name = '전남광주통합특별시 서구 통합전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 561 AND name = '상록도서관';
UPDATE elibrary SET name = '세종특별자치시교육청평생교육원', updated_at = CURRENT_TIMESTAMP WHERE id = 344 AND name = '세종특별자치시평생교육학습관';
UPDATE elibrary SET name = '전북국제협력진흥원', updated_at = CURRENT_TIMESTAMP WHERE id = 364 AND name = '전라북도 국제교류센터';
UPDATE elibrary SET name = '전북특별자치도 인재개발원', updated_at = CURRENT_TIMESTAMP WHERE id = 366 AND name = '전라북도인재개발원';
UPDATE elibrary SET name = '경기평택교육도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 472 AND name = '경기도립중앙도서관 평택분관';
UPDATE elibrary SET name = '경기포천교육도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 473 AND name = '경기도립포천분관';
UPDATE elibrary SET name = '경기평생교육학습관', updated_at = CURRENT_TIMESTAMP WHERE id = 478 AND name = '경기평생학습관';
UPDATE elibrary SET name = '부산 북구 구립도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 546 AND name = '만덕도서관';
UPDATE elibrary SET name = '한경국립대학교', updated_at = CURRENT_TIMESTAMP WHERE id = 1908 AND name = '한경대학교';
UPDATE elibrary SET name = '한국예술종합학교', updated_at = CURRENT_TIMESTAMP WHERE id = 1918 AND name = '한국예술종합대학교';
UPDATE elibrary SET name = '인천광역시 제물포구 구립도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 631 AND name = '인천송림도서관';
UPDATE elibrary SET name = '한국침례신학대학교', updated_at = CURRENT_TIMESTAMP WHERE id = 1755 AND name = '대전침례신학대학교';
UPDATE elibrary SET name = '국립목포대학교(담양캠퍼스)', updated_at = CURRENT_TIMESTAMP WHERE id = 1873 AND name = '전남도립대학교';
UPDATE elibrary SET name = '충북도립대학교', updated_at = CURRENT_TIMESTAMP WHERE id = 1901 AND name = '충북도립대학';
UPDATE elibrary SET name = '한국전통문화대학교', updated_at = CURRENT_TIMESTAMP WHERE id = 1920 AND name = '한국전통문화학교';
UPDATE elibrary SET name = '여주시립도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 598 AND name = '여주시평생학습센터';

-- 2. 지점 이름으로 저장돼 있지만 사이트는 지역 전체 전자도서관인 곳 — 지역 이름으로 찾을 수 있게 한다
UPDATE elibrary SET name = '수원시 전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 570 AND name = '선경도서관';
UPDATE elibrary SET name = '대전 서구 전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 618 AND name = '월평도서관';
UPDATE elibrary SET name = '서울중구 통합전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 650 AND name = '중구구립도서관';
UPDATE elibrary SET name = '도봉구 전자책도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 537 AND name = '도봉문화정보도서관';
UPDATE elibrary SET name = '서초구 전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 568 AND name = '서초구립반포도서관';
UPDATE elibrary SET name = '의정부시도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 621 AND name = '의정부지식정보센터';
UPDATE elibrary SET name = '강남구 전자도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 463 AND name = '강남구청';

-- 3. 사이트를 사람이 직접 확인한 곳. 두 이름을 함께 쓰는 곳은 괄호로 함께 적는다(1782 · 609 · 525).
-- 1681은 사이트 제목이 아직 옛 학교명이지만 대학 통합 뒤 이름으로 적는다('강원대학교'만 쓰면 1682와 이름이 같아진다)
UPDATE elibrary SET name = '백석대학교(백석예술대학교)', updated_at = CURRENT_TIMESTAMP WHERE id = 1782 AND name = '백석대학교';
UPDATE elibrary SET name = '옥천군 공공도서관(가양도서관)', updated_at = CURRENT_TIMESTAMP WHERE id = 609 AND name = '옥천군공공도서관';
UPDATE elibrary SET name = '나주시립도서관(빛가람시립도서관)', updated_at = CURRENT_TIMESTAMP WHERE id = 525 AND name = '나주시립도서관';
UPDATE elibrary SET name = '단양군립올누림도서관', updated_at = CURRENT_TIMESTAMP WHERE id = 531 AND name = '단양다누리도서관';
UPDATE elibrary SET name = '강원대학교 강릉캠퍼스', updated_at = CURRENT_TIMESTAMP WHERE id = 1681 AND name = '강릉원주대학교';
UPDATE elibrary SET name = 'NC', updated_at = CURRENT_TIMESTAMP WHERE id = 37 AND name = 'NCSOFT';
UPDATE elibrary SET name = '국가데이터처', updated_at = CURRENT_TIMESTAMP WHERE id = 376 AND name = '통계청 전자도서관';
