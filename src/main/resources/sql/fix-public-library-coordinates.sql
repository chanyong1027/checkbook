-- 공공도서관 좌표 오류 수정 (카카오 지오코딩 기반)
-- 2026-03-24

UPDATE public_library SET lat = 35.2013, lon = 126.8739, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '129225'; -- 광주 북구 양산도서관
UPDATE public_library SET lat = 37.4192, lon = 127.7546, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141282'; -- 양평군 양동도서관
UPDATE public_library SET lat = 37.5586, lon = 127.1833, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '741725'; -- 하남시 일가도서관
UPDATE public_library SET lat = 37.4737, lon = 127.6386, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141317'; -- 양평군 지평도서관
UPDATE public_library SET lat = 37.6465, lon = 126.6237, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '741008'; -- 구래작은도서관
UPDATE public_library SET lat = 37.4872, lon = 127.5962, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141128'; -- 양평군 용문도서관
UPDATE public_library SET lat = 37.4969, lon = 127.4846, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141011'; -- 양평도서관
UPDATE public_library SET lat = 37.4859, lon = 127.4943, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141109'; -- 양평군 어린이도서관
UPDATE public_library SET lat = 37.5434, lon = 127.1941, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141607'; -- 하남시 세미도서관
UPDATE public_library SET lat = 37.4466, lon = 127.1501, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141008'; -- 경기도교육청성남도서관
UPDATE public_library SET lat = 37.5423, lon = 127.3236, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141261'; -- 양평군 양서친환경도서관
UPDATE public_library SET lat = 35.6000, lon = 129.3656, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '131083'; -- 울산 북구 송정나래도서관
UPDATE public_library SET lat = 37.4638, lon = 127.1279, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141623'; -- 성남시 복정도서관
UPDATE public_library SET lat = 37.1881, lon = 127.5927, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141068'; -- 이천시립청미도서관
UPDATE public_library SET lat = 37.4520, lon = 127.0544, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '111476'; -- 서초구립내곡도서관
UPDATE public_library SET lat = 37.5676, lon = 127.1873, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '141622'; -- 하남시 미사도서관
UPDATE public_library SET lat = 35.2018, lon = 129.2062, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '126160'; -- 부산광역시 내리새라도서관
UPDATE public_library SET lat = 36.4710, lon = 127.2793, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '150007'; -- 대평동도서관
UPDATE public_library SET lat = 37.5306, lon = 126.6363, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '128097'; -- 청라호수도서관
UPDATE public_library SET lat = 37.5942, lon = 126.6634, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '128107'; -- 마전도서관
UPDATE public_library SET name = '반야월역사작은도서관', address = '대구광역시 동구 신서로 50', lat = 35.8725, lon = 128.7250, updated_at = CURRENT_TIMESTAMP WHERE lib_code = '127031'; -- 반야월역사작은도서관
