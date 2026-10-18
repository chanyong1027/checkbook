package com.checkbook.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 공급자 하루 예산의 DB 합계. 여러 앱 칸이 각자 증가분을 더한다(덮어쓰지 않는다).
 * PostgreSQL의 ON CONFLICT는 테스트 DB(H2)가 지원하지 않아, 두 DB에서 같은 SQL로 도는 UPDATE → INSERT를 쓴다(결정 카드 A1).
 * 트랜잭션 없이(문장마다 커밋) 불러야 한다 — PostgreSQL은 트랜잭션 안에서 INSERT가 키 충돌로 실패하면
 * 그 트랜잭션이 중단돼 재시도 UPDATE까지 실패한다.
 */
@Repository
@RequiredArgsConstructor
public class ExternalCallBudgetStore {

    private static final String UPDATE =
            "UPDATE external_call_budget SET used = used + ?, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE budget_key = ? AND usage_date = ?";
    private static final String INSERT =
            "INSERT INTO external_call_budget (budget_key, usage_date, used, updated_at)"
                    + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * delta를 더한다. 합계는 돌려주지 않는다 — 쓰기와 읽기를 한 번에 하면, 쓰기는 커밋됐는데 읽기가 실패했을 때
     * 호출자가 증가분을 되돌려 다음에 두 번 더하게 된다. 합계는 {@link #load}로 따로 읽는다.
     */
    public void add(String key, LocalDate date, int delta) {
        if (jdbcTemplate.update(UPDATE, delta, key, date) == 0) {
            try {
                jdbcTemplate.update(INSERT, key, date, delta);
            } catch (DuplicateKeyException e) {
                // 다른 칸이 같은 순간 먼저 넣었다 — 그 행에 더한다
                jdbcTemplate.update(UPDATE, delta, key, date);
            }
        }
    }

    /** 그날의 키별 합계 */
    public Map<String, Integer> load(LocalDate date) {
        Map<String, Integer> totals = new HashMap<>();
        jdbcTemplate.query("SELECT budget_key, used FROM external_call_budget WHERE usage_date = ?",
                rs -> {
                    totals.put(rs.getString("budget_key"), rs.getInt("used"));
                }, date);
        return totals;
    }
}
