package com.checkbook.common.ratelimit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 공급자 하루 예산 행. 읽고 쓰기는 {@link ExternalCallBudgetStore}가 SQL로 한다.
 * 이 엔티티는 테스트 DB(H2, ddl-auto)가 같은 모양의 테이블을 만들게 하려고 둔다 — 운영 스키마는 V7 마이그레이션이 기준.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "external_call_budget")
@IdClass(ExternalCallBudgetUsage.Key.class)
public class ExternalCallBudgetUsage {

    @Id
    @Column(name = "budget_key", length = 64)
    private String budgetKey;

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "used", nullable = false)
    private int used;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String budgetKey;
        private LocalDate usageDate;
    }
}
