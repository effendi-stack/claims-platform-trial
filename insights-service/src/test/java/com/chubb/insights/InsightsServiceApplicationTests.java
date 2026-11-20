package com.chubb.insights;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InsightsServiceApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsApplyAndSeedFxRates() {
        Integer rates = jdbc.queryForObject("SELECT count(*) FROM insights.fx_rate", Integer.class);
        assertThat(rates).isEqualTo(7);
    }
}
