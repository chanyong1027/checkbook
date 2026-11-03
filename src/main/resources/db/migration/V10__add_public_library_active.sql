-- 정보나루 참여 목록에서 빠진 도서관을 지우지 않고 표시한다(주간 목록 동기화). 기존 행은 모두 참여 중으로 시작한다.
-- 옛 앱(이 열을 모름)은 기본값 덕에 그대로 동작한다 — 블루·그린 전환 중 두 판이 같은 DB를 써도 된다
ALTER TABLE public_library ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
