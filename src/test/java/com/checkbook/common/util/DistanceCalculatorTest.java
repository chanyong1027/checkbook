package com.checkbook.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistanceCalculatorTest {

    @Test
    void kmSeoulToIncheonApprox30km() {
        double distance = DistanceCalculator.km(37.5666, 126.9784, 37.4563, 126.7052);

        assertThat(distance).isBetween(25.0, 35.0);
    }

    @Test
    void kmSamePointIsZero() {
        assertThat(DistanceCalculator.km(37.5, 127.0, 37.5, 127.0)).isLessThan(0.001);
    }
}
