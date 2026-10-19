package com.checkbook.common.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
public class DatabaseHealthProbe {

    // 외부 감시(5분 간격)가 기다리는 시간 안에 답하도록 짧게. 연결 풀이 바닥나 연결을 못 얻는 시간은
    // Hikari connectionTimeout(기본 30초)을 따른다 — 그만큼 막혀 있으면 감시 쪽 시간 초과로 DOWN이 맞다
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthProbe(DataSource dataSource) {
        // 공용 JdbcTemplate의 쿼리 타임아웃을 바꾸지 않게 따로 만든다
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    public boolean isUp() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (DataAccessException e) {
            // ERROR로 남기면 DB 장애 하나가 오류 로그 알림과 외부 감시 알림 두 갈래로 온다. 알림은 외부 감시가 맡는다
            log.warn("헬스 확인: DB 응답 없음 — {}", e.getMessage());
            return false;
        }
    }
}
