package com.checkbook.common.health;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseHealthProbeTest {

    @Test
    void SELECT_1이_되면_UP() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:health-probe;DB_CLOSE_DELAY=-1", "sa", "");

        assertThat(new DatabaseHealthProbe(dataSource).isUp()).isTrue();
    }

    @Test
    void 연결을_못_얻으면_예외_대신_DOWN() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThat(new DatabaseHealthProbe(dataSource).isUp()).isFalse();
    }
}
