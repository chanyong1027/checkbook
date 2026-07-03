-- 부하테스트용 도서관 20곳 시드 (서울시청 좌표 인근, findNearest 첫 5km 박스에 전부 걸림)
-- 실행(compose 기동 후): docker exec -i loadtest-postgres psql -U checkbook -d checkbook < scripts/loadtest/seed-libraries.sql
TRUNCATE library_availability_snapshot;
DELETE FROM public_library WHERE lib_code LIKE 'LT%';

INSERT INTO public_library
    (lib_code, name, address, lat, lon, region_name, homepage, created_at, updated_at)
SELECT
    'LT' || LPAD(i::text, 3, '0'),
    '부하테스트도서관' || i,
    '서울 중구 테스트로 ' || i,
    37.5665 + (i * 0.001),
    126.9780 + (i * 0.001),
    '서울',
    'https://lib' || i || '.example.com',
    NOW(), NOW()
FROM generate_series(1, 20) AS i;
