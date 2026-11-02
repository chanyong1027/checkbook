-- 알라딘 ItemList 종료(2026-10-30)로 추천 출처 값 ALADIN을 코드에서 지운다.
-- 엔티티가 ALADIN을 모르게 되므로, 남은 행이 있으면 앱이 그 행을 읽지 못해 홈 추천이 깨진다 — 지우기 전에 값을 바꾼다.
-- FAILED로 두면 다음 시작 때 워밍업이 그 칸을 YES24로 다시 받는다(그 전까지 화면은 '오래된 목록'으로 표시).
-- 이미 YES24로 갱신된 DB에서는 바뀌는 행이 없다. V5 시드(ALADIN)는 체크섬 때문에 고치지 않고 여기서 덮는다
UPDATE featured_section_snapshot
SET source         = 'YES24',
    status         = 'FAILED',
    failure_reason = 'ALADIN source retired (V9); refetch from YES24 on next startup'
WHERE source = 'ALADIN';
