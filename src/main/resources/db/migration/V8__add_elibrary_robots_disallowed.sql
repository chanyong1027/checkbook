-- 전자도서관 robots.txt 차단 여부 (전수 조사 결정 2026-10-20)
-- 사용자가 직접 요청한 1회 조회는 robots 예외로 열어 두되, 차단 사이트는 하루 수집 상한을 낮춘다(1,000 → 200).
-- 기본 false: 값을 채우기 전에는 모든 사이트가 지금과 같은 상한을 쓴다. 값은 robots 재조사 결과로 운영 DB에 따로 채운다
ALTER TABLE elibrary ADD COLUMN robots_disallowed BOOLEAN NOT NULL DEFAULT FALSE;
