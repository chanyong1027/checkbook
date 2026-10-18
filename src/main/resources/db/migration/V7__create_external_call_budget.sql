-- 공급자 하루 외부 호출 예산 (요청 제한 결정 2026-10-18, 리뷰 F2)
-- 앱 칸마다 메모리에서 세다가 10초마다 증가분을 더한다(used = used + delta). 재시작·블루그린 전환에도 이어서 센다.
-- 행은 지우지 않는다: 하루 공급자 4행 + 그날 쓴 전자도서관 사이트 수만큼이라 작고(날짜가 PK 앞이라 조회는 그날 행만 본다),
-- 공급자별 하루 사용 기록으로 쓴다

CREATE TABLE IF NOT EXISTS external_call_budget (
    budget_key  VARCHAR(64)  NOT NULL,
    usage_date  DATE         NOT NULL,
    used        INTEGER      NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    -- 날짜를 앞에: 10초마다 하는 "그날 전체 읽기"(WHERE usage_date = ?)가 이 인덱스를 탄다
    PRIMARY KEY (usage_date, budget_key)
);
